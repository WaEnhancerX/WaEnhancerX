#!/usr/bin/env python3
"""Compile deliberate preview regressions; setup failures are never valid negative controls."""
from pathlib import Path
import argparse,shutil,subprocess,sys,json,re,hashlib
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',required=True,type=Path)
 for key in ('deps','sdk-jars','dexkit-jar','http-api-jar','maven-repo','maven-settings'):p.add_argument('--'+key,type=Path)
 p.add_argument('--maven',default='mvn');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
 from android_media_preview_test import SOURCES,EXPECTED_TESTS
 source=ROOT/SOURCES[0];text=source.read_text(encoding='utf-8')
 monitor='''        synchronized(session) {
            if (!isCurrent(session)) return
            // Cancellation cannot remove callbacks between this check and registration.
            mainHandler.postAtTime({ if (isCurrent(session)) action() }, session, SystemClock.uptimeMillis())
        }'''
 mutations=[
 ('unbounded-progress','            if (session.progressTask != null) return','            // Deliberate mutation: enqueue every progress update','progressBurstCoalescesAndShowsLatestValue'),
 ('leaked-pending-bitmap','        pendingBitmap?.recycle()','        // Deliberate mutation: omit pending bitmap release','pendingDecodedImageRecycledOnDismiss'),
 ('late-ui-registration',monitor,'        if (!isCurrent(session)) return\n        mainHandler.postAtTime({ if (isCurrent(session)) action() }, session, SystemClock.uptimeMillis())','publicationWaitsForSessionCancellationMonitor'),
 ('uncancelled-call','        call?.cancel()','        // Deliberate mutation: keep superseded HTTP call','dismissCancelsTheActiveCallAndRemovesOwnedFile'),
 ('orphaned-replaced-temporary','if (!isCurrent(session)) ownedFile?.delete()','if (session.cancelled) ownedFile?.delete()','identityReplacementBeforeTempRegistrationRemovesOrphan')]
 def run(label,root):
  out=a.out/label;cmd=[sys.executable,str(ROOT/'qa/android_media_preview_test.py'),'--source-root',str(root),'--out',str(out),'--maven',a.maven]
  for key in ('deps','sdk_jars','dexkit_jar','http_api_jar','maven_repo','maven_settings'):
   if getattr(a,key):cmd+=['--'+key.replace('_','-'),str(getattr(a,key).resolve())]
  r=subprocess.run(cmd,capture_output=True,timeout=900);(a.out/(label+'.log')).write_bytes(r.stdout+r.stderr)
  if not (out/'result.json').is_file():raise RuntimeError(label+' did not complete compilation/tests')
  result=json.loads((out/'result.json').read_text(encoding='utf-8'));return r,result,(out/'junit.log').read_text(encoding='utf-8')
 healthy,report,_=run('healthy',ROOT)
 assert healthy.returncode==0 and report['accepted_result'] and report['tests']==EXPECTED_TESTS
 results=[]
 for name,old,new,expected in mutations:
  if text.count(old)!=1:raise RuntimeError('Mutation anchor changed: '+name)
  root=a.out/(name+'-source');root.mkdir()
  for rel in SOURCES:
   dest=root/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/rel,dest)
  shutil.copytree(ROOT/'app/src/main/res/values',root/'app/src/main/res/values')
  (root/SOURCES[0]).write_text(text.replace(old,new,1),encoding='utf-8')
  r,row,log=run(name,root)
  valid=r.returncode!=0 and row['tests']==EXPECTED_TESTS and row['failures']>0 and row['failures']==row['assertion_failures'] and expected in log
  item={'mutation':name,'tests':row['tests'],'failures':row['failures'],'assertion_failures':row['assertion_failures'],'expected_test':expected,'detected_behaviorally':valid,'mutated_sha256':hashlib.sha256((root/SOURCES[0]).read_bytes()).hexdigest()};results.append(item);print(json.dumps(item),flush=True)
  (a.out/'result.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
  if not valid:raise RuntimeError('Invalid negative control '+name)
if __name__=='__main__':main()
