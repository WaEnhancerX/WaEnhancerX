#!/usr/bin/env python3
"""Independent positive and negative controls for the source locale inventory."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile

from locale_coverage_report import explain, inventory


def table(**values):
    return {key: (value, True) for key, value in values.items()}


original = table(title="Title", body="Body %1$s")
complete_es = table(title="Título", body="Cuerpo %1$s")
ok = inventory(original, complete_es, dict(complete_es))
assert ok["complete"] is True
assert not ok["locales"]["es"]["missing"]
assert "COMPLETE" in explain(ok)

bad = inventory(original, table(title="Título", retired="Retirado"),
                table(body="Cuerpo %1$s"))
assert bad["complete"] is False
assert bad["locales"]["es"]["missing"] == ["body"]
assert bad["locales"]["es"]["extra"] == ["retired"]
assert bad["locales"]["es-419"]["missing"] == ["title"]
assert bad["es_only"] == ["retired", "title"]
assert bad["es_419_only"] == ["body"]
assert "INCOMPLETE" in explain(bad)
assert json.loads(json.dumps(bad))["locales"]["es"]["missing"] == ["body"]

different = inventory(original, complete_es, table(title="Título diferente", body="Cuerpo %1$s"))
assert different["complete"] is False
assert different["spanish_value_mismatches"] == ["title"]

with tempfile.TemporaryDirectory() as sandbox:
    root = Path(sandbox)
    for qualifier, nodes in [
        ("values", '<string name="title">Title</string><string name="body">Body</string>'),
        ("values-es", '<string name="title">Título</string>'),
        ("values-b+es+419", '<string name="body">Cuerpo</string>'),
    ]:
        folder = root / "app/src/main/res" / qualifier
        folder.mkdir(parents=True)
        (folder / "strings.xml").write_text("<resources>" + nodes + "</resources>", encoding="utf-8")
    command = [sys.executable, str(Path(__file__).with_name("locale_coverage_report.py")),
               "--source-root", str(root)]
    nonstrict = subprocess.run(command + ["--out", str(root / "inventory")],
                               text=True, capture_output=True, check=False)
    assert nonstrict.returncode == 0 and "INCOMPLETE" in nonstrict.stdout
    evidence = json.loads((root / "inventory/locale-coverage.json").read_text(encoding="utf-8"))
    assert evidence["coverage"]["locales"]["es"]["missing"] == ["body"]
    assert len(evidence["source_sha256"]) == 3
    rejected = subprocess.run(command + ["--strict"], text=True, capture_output=True, check=False)
    assert rejected.returncode == 1 and "INCOMPLETE" in rejected.stdout
    for folder in ("values-es", "values-b+es+419"):
        source = root / "app/src/main/res" / folder / "strings.xml"
        if folder == "values-es":
            source.write_text('<resources><string name="title">Título</string>'
                              '<string name="body">Cuerpo</string></resources>', encoding="utf-8")
        else:
            source.write_text('<resources><string name="title">Título</string>'
                              '<string name="body">Cuerpo</string></resources>', encoding="utf-8")
    accepted = subprocess.run(command + ["--strict"], text=True, capture_output=True, check=False)
    assert accepted.returncode == 0 and "COMPLETE" in accepted.stdout

print("PASS: complete, missing, extra, divergent, JSON, strict rejection and strict acceptance controls")
