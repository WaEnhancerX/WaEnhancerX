#!/usr/bin/env python3
"""Require named reflection-contract failures from compiling call-ending regressions."""
from pathlib import Path
import argparse, json, subprocess, sys, tempfile
ROOT=Path(__file__).resolve().parents[1]
REL=Path('app/src/main/java/com/waenhancer/xposed/features/media/CallRecordingEventContract.java')
CASES=[
 ('unvalidated_exact', '            requireVoidMethod(exact);', '            // Deliberately skip validating the preferred overload.', 'typed_after_invalid_exact_return'),
 ('suffix_owner', 'if (params.length == 0 || params[0] != logInfo) continue;', 'if (params.length == 0 || !params[0].getName().endsWith("CallLogInfo")) continue;', 'reject_unrelated_calllog_suffix'),
 ('first_ambiguous', 'throw new NoSuchMethodException("Ambiguous callEnding event");', 'return candidate;', 'reject_ambiguous_typed'),
]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',required=True,type=Path);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
 def execute(root,out):
  cmd=[sys.executable,str(ROOT/'qa/test_call_recording_event_contract.py'),'--root',str(root),'--out',str(out)]
  r=subprocess.run(cmd,capture_output=True,timeout=180)
  (a.out/(out.name+'.log')).write_bytes(r.stdout+r.stderr)
  report=out/'result.json'
  if not report.is_file():raise RuntimeError('Suite did not execute completely: '+out.name)
  result=json.loads(report.read_text(encoding='utf-8'))
  if result['count']!=46:raise RuntimeError('Incomplete selector matrix')
  return r.returncode,result
 code,healthy=execute(ROOT,a.out/'healthy')
 if code or healthy['passed']!=46:raise RuntimeError('Candidate must pass before mutations')
 source=(ROOT/REL).read_text(encoding='utf-8');results=[]
 for name,before,after,expected in CASES:
  if source.count(before)!=1:raise RuntimeError('Changed mutation anchor '+name)
  with tempfile.TemporaryDirectory(prefix='waex_ending_mutant_') as directory:
   root=Path(directory);target=root/REL;target.parent.mkdir(parents=True);target.write_text(source.replace(before,after),encoding='utf-8')
   code,result=execute(root,a.out/name)
   failures=[c for c in result['cases'] if not c['passed']]
   valid=code!=0 and any(c['case']==expected and 'AssertionError' in c['output'] for c in failures)
   results.append({'mutant':name,'completed_cases':result['count'],'failed_cases':len(failures),'expected_case':expected,'valid_negative':valid})
   print(json.dumps(results[-1]),flush=True)
   if not valid:raise RuntimeError('Mutation not rejected at its intended assertion: '+name)
 (a.out/'result.json').write_text(json.dumps({'candidate_passed':True,'mutants':results},indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':main()
