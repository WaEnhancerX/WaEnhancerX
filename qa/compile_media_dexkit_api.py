#!/usr/bin/env python3
"""Compile complete production media/view-once hooks against the supplied real DexKit API.

Only Android, BaseFeature, DexSearchEngine and Xposed are compile-only doubles.
No DexKit type is stubbed. Does not execute native discovery, WhatsApp or codecs.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

from test_media_hook_contracts import STUBS, P


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexkit-jar", required=True, type=Path)
    parser.add_argument("--kotlin-stdlib", required=True, type=Path)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    paths = [args.dexkit_jar.resolve(), args.kotlin_stdlib.resolve()]
    if not all(path.is_file() for path in paths):
        parser.error("The supplied public dependency files must exist")
    source = args.source_root / "app/src/main/java"
    production = [source / (P + path) for path in [
        "core/devkit/HookReturnTypeGuard.java",
        "features/media/MediaQualityBypassHook.java",
        "features/privacy/AntiViewOnceHook.java",
        "features/privacy/ViewOnceMessageContract.java",
    ]]
    with tempfile.TemporaryDirectory(prefix="waex_media_dexkit_api_") as directory:
        dest = Path(directory)
        doubles = []
        for relative, content in STUBS.items():
            if relative.startswith("org/luckypray/") or relative.startswith("com/whatsapp/"):
                continue
            target = dest / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
            doubles.append(target)
        run = subprocess.run(["javac", "--release", "17", "-cp", ":".join(map(str, paths)),
                              "-d", str(dest), *map(str, doubles), *map(str, production)],
                             text=True, capture_output=True, timeout=60)
        print(json.dumps({
            "result": "PASS" if run.returncode == 0 else "FAIL",
            "dexkit_types_stubbed": False,
            "complete_production_classes": {str(p.relative_to(args.source_root)):
                hashlib.sha256(p.read_bytes()).hexdigest() for p in production},
            "dependencies": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
            "compiler_output": run.stdout + run.stderr,
            "scope": "Real public DexKit API type-check; no native discovery or Android/host execution",
        }, indent=2))
        if run.returncode:
            raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
