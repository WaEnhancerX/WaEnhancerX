#!/usr/bin/env python3
"""Negative controls for localization; preserve original sources and require assertion failures."""
from pathlib import Path
import argparse,tempfile,shutil,subprocess,json,sys
from localization_support import stage_string_resources
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,required=True);args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
CASES=[
('ignore_selected_language','UiText.java','return DisplayLocalePolicy.resolve(code, system);','return java.util.Locale.ENGLISH;','test_es419_runtime.py','Spanish resource'),
('host_resource_owner','UiText.java','source = new Source(app, module, preferences);','source = new Source(app, app, preferences);','test_es419_runtime.py','Spanish resource'),
('recreate_resource_context_per_label','UiText.java','old != null && old.source == current','false && old != null && old.source == current','test_es419_runtime.py','unbounded per-label'),
('lose_legacy_locale','DisplayLocalePolicy.java','if (code.equals("es")) return "es-419";','if (code.equals("es")) return "en";','test_es419_runtime.py','Spanish locale es'),
]
results=[]
with tempfile.TemporaryDirectory(prefix='waex_l10n_mutants_') as temp:
 base=Path(temp)
 for name,file,before,after,test,expected in CASES:
  r=base/name;(r/'app/src/main').mkdir(parents=True);(r/'qa').mkdir()
  shutil.copytree(ROOT/'app/src/main/java',r/'app/src/main/java')
  stage_string_resources(ROOT, r/'app/src/main/res', {})
  for n in ['localization_support.py','test_es419_runtime.py']:shutil.copy2(ROOT/'qa'/n,r/'qa'/n)
  target=r/'app/src/main/java/com/waenhancer/utils'/file;code=target.read_text();assert before in code,(name,'inert');target.write_text(code.replace(before,after))
  p=subprocess.run([sys.executable,str(r/'qa'/test)],cwd=r,capture_output=True,text=True,timeout=150)
  output=p.stdout+p.stderr;(args.out/(name+'.log')).write_text(output)
  compiled=('AssertionError: '+expected) in output
  results.append({'case':name,'exit':p.returncode,'compiled_then_assertion_failure':compiled})
  if p.returncode==0 or not compiled:raise AssertionError(f'{name}: expected a runtime assertion, not compiler/tool failure: {output[-1800:]}')
(args.out/'results.json').write_text(json.dumps(results,indent=2));print('PASS: 4 localization mutants compiled and failed the expected production behavior assertion')
