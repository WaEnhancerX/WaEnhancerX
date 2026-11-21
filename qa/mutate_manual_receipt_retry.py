#!/usr/bin/env python3
"""Require five compiled integrated manual-receipt retry regressions to fail assertions."""
import argparse, json, os, shutil, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def select_mutations(mutations, start_at):
 """Return the named suffix without marking preceding cases as executed."""
 names=[case[0] for case in mutations]
 if start_at not in names:raise ValueError('Unknown mutation start: '+start_at)
 return mutations[names.index(start_at):]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',required=True,type=Path);p.add_argument('--kotlinc',default=os.environ.get('KOTLINC') or shutil.which('kotlinc'));p.add_argument('--start-at',default='lost-delayed-retry',help='Run this mutation and later cases; preceding cases are not counted as executed');a=p.parse_args()
 if not a.kotlinc:p.error('Supply a complete Kotlin compiler distribution')
 mutations=[
 ('lost-delayed-retry','BlueOnReplyHook.java','if (accepted) restoreRecipe();','if (accepted) { /* mutant */ }'),
 ('lost-synchronous-retry','BlueOnReplyHook.java','if (vetoed) restoreRecipe();','if (vetoed) { /* mutant */ }'),
 ('duplicate-veto-recovery','BlueOnReplyHook.java','if (vetoed) return;','/* mutant: duplicate recovery */'),
 ('missing-enqueue-ack','BlueOnReplyHook.java','PENDING_JOBS.remove(recipient, original);','/* mutant: accepted recipe retained */'),
 ('lost-delivery-only-retry','HideReceiptsHook.java','if (BlueOnReplyHook.isManualChatReceipt(job)) {\n                                BlueOnReplyHook.recordSuppressedReadJob(job);\n                            }','/* mutant: delivery-only veto loses retry */')]
 try:selected=select_mutations(mutations,a.start_at)
 except ValueError as error:p.error(str(error))
 # Keep selection regressions on this entrypoint without changing workflow
 # permissions. Invalid selections above never create evidence directories.
 subprocess.run([sys.executable,str(ROOT/'qa/test_manual_receipt_mutation_selection.py')],check=True,timeout=30)
 a.out.mkdir(parents=True,exist_ok=False);rows=[]
 for label,name,old,new in selected:
  source=a.out/(label+'-source');shutil.copytree(ROOT/'app',source/'app')
  file=source/'app/src/main/java/com/waenhancer/xposed/features/privacy'/name;text=file.read_text(encoding='utf-8')
  if text.count(old)!=1:raise RuntimeError('Mutation anchor changed: '+label)
  file.write_text(text.replace(old,new),encoding='utf-8');out=a.out/label
  result=subprocess.run([sys.executable,str(ROOT/'qa/test_manual_receipt_retry.py'),'--source-root',str(source),'--out',str(out),'--kotlinc',a.kotlinc],capture_output=True,timeout=180)
  (a.out/(label+'.log')).write_bytes(result.stdout+result.stderr)
  if not (out/'result.json').is_file():raise RuntimeError('Mutation failed before completing the behavioral matrix: '+label)
  report=json.loads((out/'result.json').read_text(encoding='utf-8'));log=(out/'behavior.log').read_text(encoding='utf-8')
  valid=result.returncode==1 and report['completed'] and report['scenarios']==14 and report['failures']>0 and 'AssertionError:' in log
  rows.append({'mutation':label,'compiled_and_completed':report['completed'],'scenarios':report['scenarios'],'failed_scenarios':report['failures'],'detected_behaviorally':valid})
  print(json.dumps(rows[-1]),flush=True)
  if not valid:raise RuntimeError('Mutation survived or failed outside its behavior checks: '+label)
 (a.out/'result.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
 print(json.dumps({'completed_mutations':len(rows),'total_mutations':len(mutations),
                   'start_at':a.start_at,'full_matrix':len(rows)==len(mutations)}),flush=True)
if __name__=='__main__':main()
