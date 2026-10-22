#!/usr/bin/env python3
"""Behavioral negative controls on public source in disposable copies.
Never runs real Android root; compilation/infrastructure errors are not detections.
"""
import argparse,json,shutil,subprocess,sys,tempfile
from pathlib import Path
U='app/src/main/java/com/waenhancer/utils/'
G='app/src/main/java/com/waenhancer/xposed/core/VersionGuard.kt'
H='app/src/main/java/com/waenhancer/xposed/features/privacy/HideReceiptsHook.java'
CASES=[
 ('target-substring',U+'DeclaredWhatsAppTargets.java','PACKAGES.contains(packageName)','(PACKAGES.contains(packageName) || packageName.contains("whatsapp"))','test_declared_targets.py'),
 ('target-case-fold',U+'DeclaredWhatsAppTargets.java','PACKAGES.contains(packageName)','PACKAGES.contains(packageName.toLowerCase(java.util.Locale.ROOT))','test_declared_targets.py'),
 ('unavailable-admitted',U+'UniversalVersionValidator.kt','RULES_UNAVAILABLE(false)','RULES_UNAVAILABLE(true)','test_runtime_diagnostics.py'),
 ('foreign-resource-owner',U+'UniversalVersionValidator.kt','if (resources.getResourceName(id) != "$MODULE_PACKAGE:array/universal_supported_versions") return null','if (false) return null','test_runtime_diagnostics.py'),
 ('foreign-module-context',U+'UniversalVersionValidator.kt','if (moduleContext.packageName != MODULE_PACKAGE) return null','if (false) return null','test_runtime_diagnostics.py'),
 ('resource-context-loads-code',U+'UniversalVersionValidator.kt','Context.CONTEXT_IGNORE_SECURITY','Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE','test_runtime_diagnostics.py'),
 ('fatal-resource-error-hidden',U+'UniversalVersionValidator.kt','} catch (_: LinkageError) {\n            null','} catch (_: Throwable) {\n            null','test_runtime_diagnostics.py'),
 ('unknown-means-stopped',U+'InjectionEvidence.kt','runningPids == null -> InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE','runningPids == null -> InjectionEvidence.PROCESS_STOPPED','test_runtime_diagnostics.py'),
 ('stale-cache-kept',U+'XposedRuntimeInspector.kt','cachedSnapshot = result //','if (result != null) cachedSnapshot = result //','test_runtime_diagnostics.py'),
 ('foreign-process-uid-admitted',U+'XposedRuntimeInspector.kt','parts[2].toIntOrNull() != expectedUids[parts[1]] || ','','test_runtime_diagnostics.py'),
 ('foreign-package-profile-admitted',U+'XposedRuntimeInspector.kt',' && AndroidUserScope.userIdForUid(info.uid) == userId','','test_runtime_diagnostics.py'),
 ('foreign-caller-profile-admitted',U+'XposedRuntimeInspector.kt','AndroidUserScope.matchingUserId(context.applicationInfo.uid, Process.myUid())','AndroidUserScope.userIdForUid(context.applicationInfo.uid)','test_runtime_diagnostics.py'),
 ('pid-list-globbing',U+'XposedRuntimeInspector.kt',r'set -f\ncommand -v pidof','command -v pidof','test_runtime_diagnostics.py'),
 ('timestamp-after-command',U+'XposedRuntimeInspector.kt','.copy(observedAtElapsed = startedAtElapsed)','.copy(observedAtElapsed = SystemClock.elapsedRealtime())','test_runtime_diagnostics.py'),
 ('obsolete-pause',G,'if (generation != decisionGeneration.get() || decision.allowed) return@runOnUiThread','if (false) return@runOnUiThread','test_runtime_diagnostics.py'),
 ('custom-is-verified',U+'TargetRuntimePresentation.kt','decision == UniversalVersionValidator.Decision.CUSTOM_RULE -> Status("Injected • Custom version allowance; unverified", Severity.REVIEW)','decision == UniversalVersionValidator.Decision.CUSTOM_RULE -> Status("Injected • Custom version allowance; unverified", Severity.OK)','test_runtime_diagnostics.py'),
 ('partial-output-promoted',U+'XposedRuntimeInspector.kt','val lines = output?.lineSequence()','val lines = (output?.takeIf { it.contains("END|WAEX_PROCESS_SNAPSHOT") } ?: output?.plus("\\nEND|WAEX_PROCESS_SNAPSHOT"))?.lineSequence()','test_runtime_diagnostics.py'),
 ('receipt-argument-fallback',H,'String captureJid = canonicalJid(jid);','String captureJid = canonicalJid(jid); if (captureJid == null) captureJid = findJidInArguments(param.args);','test_receipt_job_execution_contract.py'),
 ('receipt-participant-exemption',H,'&& !hasExplicitReceiptIds(jid, param.args))','&& !hasExplicitReceiptIds(resolveConversationTarget(jid, participant), param.args))','test_receipt_job_execution_contract.py'),
]
def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--out',type=Path,required=True);p.add_argument('--only',nargs='*');a=p.parse_args()
 a.out.mkdir(parents=True,exist_ok=False);results=[]
 for name,path,old,new,test in CASES:
  if a.only and name not in a.only:continue
  with tempfile.TemporaryDirectory(prefix='waex-negative-') as folder:
   root=Path(folder)/'source'
   shutil.copytree(a.root,root,ignore=shutil.ignore_patterns('.git','.gradle','build','__pycache__','*.apk','*.ttf','*.otf','*.jks','*.keystore'))
   f=root/path;s=f.read_text();assert s.count(old)==1,(name,'source contract drift',s.count(old));f.write_text(s.replace(old,new))
   try:
    r=subprocess.run([sys.executable,str(root/'qa'/test)],cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
   except subprocess.TimeoutExpired:raise RuntimeError(name+': timeout is not a behavioral detection')
   (a.out/(name+'.log')).write_text(r.stdout)
   behavioral=r.returncode!=0 and 'COMPILE_OK:' in r.stdout and ('ASSERTION|' in r.stdout or 'AssertionError:' in r.stdout)
   if not behavioral:raise RuntimeError(name+': not rejected by behavior after compilation\n'+r.stdout[-2500:])
   results.append({'name':name,'test':test,'exit':r.returncode,'compiled':True,'behaviorally_rejected':True})
   (a.out/'results.json').write_text(json.dumps(results,indent=2));print('REJECTED:',name,flush=True)
 print(f'PASS: {len(results)} compiled unsafe variants rejected; no Android/LSPosed claim')
if __name__=='__main__':main()
