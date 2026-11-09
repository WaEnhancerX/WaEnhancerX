#!/usr/bin/env python3
"""Reject compiling opt-out regressions; never treat setup failure as mutation success."""
from pathlib import Path
import argparse,tempfile,subprocess,json,sys,hashlib
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--java',default='java');p.add_argument('--kotlin-home',type=Path);a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=False);root=Path(__file__).resolve().parents[1]
relative=Path('app/src/main/java/com/waenhancer/ui/screens/calls/CallsHubScreen.kt');original=(root/relative).read_text(encoding='utf-8')
cases=[
 ('stale_target_action','if (prefManager.targetEpoch == callPrivacyEpoch) {','if (true) {',2),
 ('ignore_explicit_false','mutableStateOf(prefManager.getBoolean("call_privacy_enabled", legacyEnabled))','mutableStateOf(prefManager.getBoolean("call_privacy_enabled", legacyEnabled) || legacyEnabled)',1),
 ('stale_target_mode','var callPrivacyMode by remember(prefManager.targetEpoch)','var callPrivacyMode by remember',1),
 ('rewrite_on_entry','    var callRejectType','    LaunchedEffect(Unit) { prefManager.putString("call_privacy", "1") }\n    var callRejectType',1),
 ('replace_custom_policy_on_enable','if (next && callPrivacyMode == "0") {','if (next && (callPrivacyMode == "0" || callPrivacyMode == "3" || callPrivacyMode == "4")) {',2),
]
results=[]
with tempfile.TemporaryDirectory(prefix='waex_calls_mutants_') as temp:
 for name,before,after,count in cases:
  if original.count(before)!=count:raise RuntimeError('Mutation anchor changed: '+name)
  dest=Path(temp)/name/relative;dest.parent.mkdir(parents=True);dest.write_text(original.replace(before,after),encoding='utf-8')
  out=a.out/name;cmd=[sys.executable,str(root/'qa/test_call_privacy_optout.py'),'--source-root',str(Path(temp)/name),'--out',str(out),'--java',a.java]
  if a.kotlin_home:cmd+=['--kotlin-home',str(a.kotlin_home)]
  r=subprocess.run(cmd,capture_output=True,timeout=150);(a.out/(name+'.log')).write_bytes(r.stdout+r.stderr)
  path=out/'result.json'
  if not path.is_file():raise RuntimeError('Mutation did not finish the compiled suite: '+name+' '+(r.stdout+r.stderr).decode(errors='replace')[-1800:])
  result=json.loads(path.read_text());valid=result['compiled'] and result['cases']==36 and result['failures']>0 and r.returncode!=0 and b'REGRESSION_CASES=' in (out/'run.log').read_bytes()
  row={'case':name,'accepted_negative_control':valid,'executed_cases':result['cases'],'failed_cases':result['failures'],'source_sha256':result['source_sha256']};results.append(row)
  print(json.dumps(row),flush=True)
  if not valid:raise RuntimeError('Mutation survived or failed for wrong reason: '+name)
(a.out/'results.json').write_text(json.dumps(results,indent=2)+'\n');print('PASS: five compiled regressions rejected by behavioral assertions')
