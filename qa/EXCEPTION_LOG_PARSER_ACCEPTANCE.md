# Quoted log expression parser acceptance

The static exception privacy guard did not enter its existing quoted-text state.
A closing parenthesis inside a Java/Kotlin string could therefore end an extracted
log argument before a later `error.getMessage()` payload. This was a detector
false negative reproduced with synthetic source, not a newly observed production
log leak.

The correction starts quote tracking for double-quoted strings and single-quoted
characters. The existing escape handling and malformed-call rejection then apply.
The unsafe-payload expression, five production paths, minimum log-site checks and
additional production assertions are unchanged.

## Required check

Run from the repository root with Python 3:

```sh
python qa/test_exception_message_privacy.py
```

The existing production guard must pass, followed by **21 parser/payload controls**:
15 exact-expression cases, one adjacent-call sequence and five incomplete-call
cases. They cover literal parentheses, escaped quotes/backslashes, character
literals, nested calls, multiline expressions and Kotlin interpolation payloads.

## Baseline and mutation controls

The V38 baseline script at `5cd69d8` has SHA-256
`0ec82782b5183c5d498672a615f7a68af08119ae63f9f46c5b5a830709225bde`.
Keep it separate from the candidate. This probe runs the same candidate controls
against each exact parser definition; a parsing/import error is not accepted as
evidence of a rejected regression.

```sh
git show 5cd69d8:qa/test_exception_message_privacy.py > /tmp/waex-log-parser-baseline.py
python - /tmp/waex-log-parser-baseline.py <<'PY'
import ast
from pathlib import Path
import runpy
import sys

candidate = Path("qa/test_exception_message_privacy.py").read_text()
guard = runpy.run_path("qa/test_exception_message_privacy.py")
variants = [
    ("baseline", Path(sys.argv[1]).read_text(), False),
    ("candidate", candidate, True),
    ("mutant_no_escape", candidate.replace("escaped=True", "escaped=False", 1), False),
    ("mutant_accept_incomplete", candidate.replace(
        "raise AssertionError('Malformed log expression')", "break", 1), False),
]
for name, source, expected_pass in variants:
    parser = next(node for node in ast.parse(source).body
                  if isinstance(node, ast.FunctionDef) and node.name == "hook_logs")
    namespace = {}
    exec(compile(ast.Module(body=[parser], type_ignores=[]), name, "exec"), namespace)
    try:
        count = guard["check_log_parser"](namespace["hook_logs"])
    except AssertionError as error:
        if expected_pass:
            raise
        print(name, "EXPECTED_ASSERTION_FAILURE", str(error))
    else:
        assert expected_pass and count == 21, (name, count)
        print(name, "PASS", count)
PY
```

The baseline fails at the closing-parenthesis payload case. Disabling escapes
fails at the escaped-quote case; accepting incomplete calls fails at the missing
call delimiter case. These are separate negative controls, not additional passing
production tests.

## Limits

This is a focused source scanner for the selected literal `XposedBridge.log`
sites, not a complete Java/Kotlin parser or an audit of every logging API. It does
not execute Android, LSPosed, WhatsApp, actual log delivery or a signed APK build.
The 21 controls verify extraction and payload detection, not privacy behavior on
a device.
