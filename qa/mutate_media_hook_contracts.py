#!/usr/bin/env python3
"""Compatibility entry point for the current complete media mutation suite.

The v2.0.6 suite additionally compiles field/callback checks against the real DexKit
API. Supply explicit dependency paths; an unavailable toolchain is never a killed
mutation. Historical mutation totals remain in Git history, not today's coverage.
"""
from pathlib import Path
import argparse,os,subprocess,sys

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True)
    p.add_argument('--dexkit-jar',type=Path,default=os.environ.get('WAEX_QA_DEXKIT_JAR'))
    p.add_argument('--kotlin-stdlib',type=Path,default=os.environ.get('WAEX_QA_KOTLIN_STDLIB'))
    a=p.parse_args()
    if a.dexkit_jar is None or a.kotlin_stdlib is None:
        p.error('Specify --dexkit-jar and --kotlin-stdlib (or the documented WAEX_QA_* variables)')
    command=[sys.executable,str(Path(__file__).with_name('mutate_media_v206_hardening.py')),
             '--out',str(a.evidence),'--dexkit-jar',str(a.dexkit_jar),'--kotlin-stdlib',str(a.kotlin_stdlib)]
    return subprocess.call(command)
if __name__=='__main__':raise SystemExit(main())
