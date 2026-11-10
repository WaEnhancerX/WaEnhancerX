#!/usr/bin/env python3
"""Compile the current production MediaQuality hook against genuine DexKit 2.2 and execute its field/value contract in JVM."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile,os
from test_media_hook_contracts import STUBS,P
parser=argparse.ArgumentParser()
parser.add_argument("--dexkit-jar",type=Path,required=True)
parser.add_argument("--kotlin-stdlib",type=Path,required=True)
parser.add_argument("--out",type=Path,required=True)
parser.add_argument("--source-root",type=Path,default=Path(__file__).resolve().parents[1])
a=parser.parse_args()
root=a.source_root.resolve()
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
sources=[
 root/"app/src/main/java/com/waenhancer/xposed/core/devkit/HookReturnTypeGuard.java",
 root/"app/src/main/java/com/waenhancer/xposed/features/media/MediaQualityBypassHook.java",
 root/"app/src/main/java/com/waenhancer/xposed/features/media/MediaQualitySelectorContract.java",
 root/"app/src/main/java/com/waenhancer/xposed/features/media/ProcessImageQualityContract.java",
 root/"qa/fixtures/media_hooks/MediaProcessFieldContractTest.java"]
with tempfile.TemporaryDirectory(prefix="waex_media_v206_") as tmp:
 build=Path(tmp);extra=[]
 for rel,text in STUBS.items():
  if rel.startswith("org/luckypray/") or rel.startswith("com/whatsapp/"):continue
  d=build/rel;d.parent.mkdir(parents=True,exist_ok=True);d.write_text(text,encoding="utf-8");extra.append(d)
 deps=[a.dexkit_jar.resolve(),a.kotlin_stdlib.resolve()]
 cp=os.pathsep.join(map(str,deps))
 compile_cmd=["javac","--release","17","-cp",cp,"-d",str(build),*map(str,extra),*map(str,sources)]
 compiled=subprocess.run(compile_cmd,text=True,capture_output=True,timeout=90)
 (out/"compile.log").write_text(compiled.stdout+compiled.stderr,encoding="utf-8")
 checked=0;failed=0;rc=None
 if compiled.returncode==0:
  execution=subprocess.run(["java","-cp",os.pathsep.join([str(build),cp]),"com.waenhancer.xposed.features.media.MediaProcessFieldContractTest"],
                           text=True,capture_output=True,timeout=60)
  (out/"execution.log").write_text(execution.stdout+execution.stderr,encoding="utf-8")
  import re
  m=re.search(r"FIELD_CONTRACT checks=(\d+) failures=(\d+)",execution.stdout)
  if m:checked=int(m.group(1));failed=int(m.group(2))
  rc=execution.returncode
 report={"status":"PASS" if compiled.returncode==0 and rc==0 and checked==56 and failed==0 else "FAIL",
         "compiler_exit":compiled.returncode,"runtime_exit":rc,"checks":checked,"failed":failed,
         "sources_sha256":{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
         "genuine_dexkit_sha256":hashlib.sha256(deps[0].read_bytes()).hexdigest(),
         "scope":"full production MediaQuality class compiled against genuine DexKit types; JVM field semantics and property mapping fixture; NOT a WhatsApp hook/codec/Pixel/ARM64 test"}
 (out/"result.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
 print(json.dumps(report),flush=True)
 if report["status"]!="PASS":raise SystemExit(1)
