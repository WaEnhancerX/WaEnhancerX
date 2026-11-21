#!/usr/bin/env python3
"""Counterexamples against the actual manual sender and immutable action scope."""
from pathlib import Path
import argparse,json,shutil,subprocess,sys,tempfile
R=Path(__file__).resolve().parents[1];A=Path('app/src/main/java/com/waenhancer/xposed/features/privacy')
FILES=['BlueOnReplyHook.java','ExplicitReadReceiptPolicy.java','RecipientReceiptAllowance.java','BoundedReceiptAllowance.java','ReceiptJidPolicy.java','PendingReadReceiptStore.java']
CASES=[
 ('lose_action_token','BlueOnReplyHook.java','                XposedHelpers.setAdditionalInstanceField(job, ExplicitReadReceiptPolicy.JOB_GRANT_KEY, grant);','                // Deliberately omit immutable consent.','sender did not attach immutable action grant'),
 ('widen_to_participant','ExplicitReadReceiptPolicy.java','\n                || !grant.recipient.equals(ReceiptJidPolicy.canonical(policyTarget))','', 'participant group replaced explicit action recipient'),
 ('add_unapproved_id','ExplicitReadReceiptPolicy.java','return ids.isEmpty() ? null : new JobGrant(exact, ids);','ids.add("M2"); return ids.isEmpty() ? null : new JobGrant(exact, ids);','unrelated host String[] granted M2'),
 ('ignore_delivery_veto','ExplicitReadReceiptPolicy.java','if (hideDelivery || grants == null || !(token instanceof JobGrant)) return false;','if (grants == null || !(token instanceof JobGrant)) return false;','explicit action bypassed Ghost/delivery')]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
 def run(root,name):
  out=a.out/name;cmd=[sys.executable,str(R/'qa/test_manual_receipt_grant.py'),'--source-root',str(root),'--out',str(out)]
  proc=subprocess.run(cmd,capture_output=True,timeout=100);(a.out/(name+'.log')).write_bytes(proc.stdout+proc.stderr)
  if not (out/'result.json').is_file():raise RuntimeError('Compilation/setup failed: '+name)
  return proc,json.loads((out/'result.json').read_text(encoding='utf-8')),(out/'behavior.log').read_text(encoding='utf-8')
 proc,r,log=run(R,'healthy')
 if proc.returncode or r['checks']!=26:raise RuntimeError('Healthy sender must pass all26 assertions')
 rows=[]
 for name,file,before,after,expected in CASES:
  with tempfile.TemporaryDirectory(prefix='waex_manual_grant_') as directory:
   root=Path(directory);(root/A).mkdir(parents=True)
   for f in FILES:shutil.copy2(R/A/f,root/A/f)
   p=root/A/file;text=p.read_text(encoding='utf-8')
   if text.count(before)!=1:raise RuntimeError('Mutation anchor changed: '+name)
   p.write_text(text.replace(before,after),encoding='utf-8');proc,r,log=run(root,name)
   valid=proc.returncode!=0 and r['compile_exit']==0 and ('java.lang.AssertionError: '+expected) in log
   rows.append({'mutation':name,'compiled':r['compile_exit']==0,'valid_negative':valid,'expected_assertion':expected});print(json.dumps(rows[-1]),flush=True)
   if not valid:raise RuntimeError('Mutation survived or failed for wrong reason: '+name)
 (a.out/'result.json').write_text(json.dumps({'healthy_checks':26,'mutants':rows},indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':main()
