#!/usr/bin/env python3
"""Compile the real root runner; no privileged subprocess is executed."""
import argparse, hashlib, json, re, shutil, subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-root',type=Path,default=ROOT)
    p.add_argument('--out',type=Path)
    p.add_argument('--expect-failures',type=int,default=0)
    a=p.parse_args()
    import tempfile
    with tempfile.TemporaryDirectory(prefix='waex-root-deadline-') as temporary:
        out=a.out.resolve() if a.out else Path(temporary)
        out.mkdir(parents=True,exist_ok=True)
        production=a.source_root.resolve()/'app/src/main/java/com/waenhancer/xposed/utils/RootCommandRunner.java'
        fixture=ROOT/'qa/TestRootCommandDeadline.java'
        stage=out/'sources';stage.mkdir(exist_ok=True)
        for src in (production,fixture): shutil.copyfile(src,stage/src.name)
        commands=[['javac','--release','17','-d',str(out/'classes'),str(stage/production.name),str(stage/fixture.name)],
                  ['java','-cp',str(out/'classes'),'com.waenhancer.xposed.utils.TestRootCommandDeadline']]
        compiled=subprocess.run(commands[0],capture_output=True,text=True,timeout=60)
        (out/'compile.log').write_text(compiled.stdout+compiled.stderr)
        if compiled.returncode: raise RuntimeError((out/'compile.log').read_text())
        run=subprocess.run(commands[1],capture_output=True,text=True,timeout=30)
        (out/'behavior.log').write_text(run.stdout+run.stderr)
        match=re.search(r'RESULT tests=(\d+) failures=(\d+)',run.stdout)
        tests,failures=map(int,match.groups()) if match else (0,-1)
        accepted=tests==13 and failures==a.expect_failures and run.returncode==(1 if failures else 0)
        result={'tests':tests,'failures':failures,'accepted':accepted,'compile_exit':compiled.returncode,'run_exit':run.returncode,
                'source_sha256':hashlib.sha256(production.read_bytes()).hexdigest(),
                'fixture_sha256':hashlib.sha256(fixture.read_bytes()).hexdigest(),'commands':commands,
                'scope':'Complete RootCommandRunner, real unprivileged sh child and controlled startup/stream faults. No su, Android service, app restart or privileged side effect. Timeout cannot undo a command already executed.'}
        (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
        print(run.stdout,end='');print(json.dumps(result))
        if not accepted: raise SystemExit(1)

if __name__=='__main__':main()
