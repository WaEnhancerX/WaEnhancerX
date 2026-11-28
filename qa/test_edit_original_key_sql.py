#!/usr/bin/env python3
"""Execute the literal production alias SQL in SQLite with a minimal compatible schema.

This checks SQL selection semantics, not Android SQLite or the whole store.
"""
import argparse
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path,
                    default=root / "app/src/main/java/com/waenhancer/xposed/core/db/EditMessageStore.kt")
args = parser.parse_args()
source = args.source.read_text(encoding="utf-8")
body = source.split("fun resolveOriginalKey(", 1)[1].split("fun getCurrentMessageFromWhatsApp(", 1)[0]
match = re.search(r'db\.rawQuery\(\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+),', body)
if not match:
    raise AssertionError("Cannot identify the literal production alias query")
sql = "".join(json.loads(part) for part in re.findall(r'"(?:\\.|[^"\\])*"', match.group(1)))
results = []
for name, messages, edits, expected in [
    ("unique", [(1, 10, "alias")], [(1, "original")], ["original"]),
    ("absent", [], [], []),
    ("unedited", [(1, 10, "alias")], [], [None]),
    ("two-originals", [(1, 10, "alias"), (2, 20, "alias")], [(1, "A"), (2, "B")], ["A", "B"]),
    ("one-unlinked", [(1, 10, "alias"), (2, 20, "alias")], [(1, "A")], ["A", None]),
    ("same-original", [(1, 10, "alias"), (2, 20, "alias")], [(1, "A"), (2, "A")], ["A", "A"]),
    ("duplicate-info", [(1, 10, "alias")], [(1, "A"), (1, "B")], ["A", "B"]),
    ("null-original", [(1, 10, "alias")], [(1, None)], [None]),
    ("bound", [(1, 10, "alias"), (2, 20, "alias"), (3, 30, "alias")],
     [(1, "A"), (2, "B"), (3, "C")], None),
]:
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE TABLE message(_id INTEGER PRIMARY KEY, chat_row_id INTEGER, key_id TEXT)")
        db.execute("CREATE INDEX message_key_idx ON message(key_id)")
        db.execute("CREATE TABLE message_edit_info(message_row_id INTEGER, original_key_id TEXT)")
        db.executemany("INSERT INTO message VALUES(?,?,?)", messages)
        db.executemany("INSERT INTO message_edit_info VALUES(?,?)", edits)
        actual = [row[0] for row in db.execute(sql, ("alias",))]
        if expected is None:
            assert len(actual) == 2 and all(x in ("A", "B", "C") for x in actual), (name, actual)
        else:
            assert sorted(actual, key=repr) == sorted(expected, key=repr), (name, actual, expected)
        assert list(db.execute(sql, ("missing-key",))) == [], (name, "parameter isolation")
        results.append({"case": name, "rows": len(actual), "pass": True})
print(json.dumps({"sqlite": sqlite3.sqlite_version, "cases": results, "checks": len(results) * 2}))
