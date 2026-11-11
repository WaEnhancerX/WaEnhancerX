#!/usr/bin/env python3
"""Compile and reject six deliberately broken reporting variants."""
from pathlib import Path
import argparse,tempfile,subprocess,sys,json,shutil
R=Path(__file__).resolve().parents[1];P=argparse.ArgumentParser();P.add_argument('--out',required=True,type=Path);a=P.parse_args();a.out.mkdir(parents=True,exist_ok=False)
relative=Path('app/src/main/java/com/waenhancer/xposed/core/FeatureRegistry.java');text=(R/relative).read_text(encoding='utf-8');main=Path('app/src/main/java/com/waenhancer/xposed/MainHook.java')
changes=[
 ('startup_report_restored','            // MainHook publishes the complete catalogue after initializeAll().','            if (entry.installation == Installation.STARTUP) reportInjectedHooks(reportingContext, reportingPackage);\n            // MainHook publishes the complete catalogue after initializeAll().'),
 ('lazy_report_lost','\n                reportInjectedHooks(reportingContext, reportingPackage);','\n                // Deliberately omit lazy state publication.'),
 ('failure_marked_active','            entry.status = "FAILED";','            entry.status = "ACTIVE";'),
 ('snapshot_hides_errors','                obj.put("status", entry.status);','                obj.put("status", "ACTIVE");'),
 ('stale_retry_error','            entry.error = null; // A successful retry must not retain an earlier failure.','            // Deliberately retain failure on success.'),
 ('lazy_optout_report_lost','                    reportInjectedHooks(reportingContext, reportingPackage);','                    // Deliberately omit the opt-out transition.')]
results=[]
for name,old,new in changes:
 if text.count(old)!=1:raise RuntimeError('Mutation anchor changed: '+name)
 with tempfile.TemporaryDirectory(prefix='waex_report_mutant_') as tmp:
  root=Path(tmp);f=root/relative;f.parent.mkdir(parents=True);f.write_text(text.replace(old,new),encoding='utf-8')
  m=root/main;m.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(R/main,m)
  out=a.out/name
  run=subprocess.run([sys.executable,str(R/'qa/test_startup_hook_reporting.py'),'--source-root',str(root),'--out',str(out)],capture_output=True,timeout=100)
  (a.out/(name+'.log')).write_bytes(run.stdout+run.stderr)
  if not (out/'result.json').is_file():raise RuntimeError('Mutation did not execute: '+name)
  result=json.loads((out/'result.json').read_text());valid=run.returncode!=0 and result['compile_exit']==0 and result['cases']==13 and result['failures']>0
  record={'mutant':name,'valid_negative':valid,'cases':result['cases'],'failures':result['failures']};results.append(record);print(json.dumps(record),flush=True)
  if not valid:raise RuntimeError('Undetected regression '+name)
(a.out/'results.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
