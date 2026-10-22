# Root startup, output acknowledgement and bounded work

`RootCommandRunner` now spends a single timeout budget on queueing, process
startup, process completion and output capture. Two daemon starter threads and a
bounded queue prevent a burst of blocked starts from creating unlimited workers.
An expired or interrupted caller abandons its request; a process that starts
after abandonment is destroyed rather than handed to an expired caller.

Output is acknowledged only after EOF and successful stream close. A nonzero
exit, failed read or close, unfinished drain, invalid request or exceeded budget
returns null. Ordinary capture still drains output beyond its 8,192-character
capture ceiling. `runBounded` additionally rejects truncation of a document.
Caller interruption remains set. A fatal starter error keeps its identity.

## Reproduce

```sh
python3 qa/test_root_command_deadline.py --out /tmp/waex-root-deadline
python3 qa/test_restart_safety.py
```

The first suite compiles the complete production runner and executes 13 scenarios
with controlled Process/stream faults and one real unprivileged `sh` child.
The same fixture executes all 13 against integration V38 (`5cd69d8`) and detects
nine assertion failures. A separate review reproduced an unchecked stream failure
being returned as successful empty output; that case is included in the suite.
Compilation failure is not a successful negative control.

## Device acceptance still required

Exercise a slow/denied root prompt, quick repeated refreshes, cancellation and a
subsequent successful command under the actual root implementation. Check that
the UI recovers and no late child remains owned by an expired request. These
tests do not execute `su`, app restarts or package installation. A timeout cannot
undo a command that has already executed. An uninterruptible platform startup
can occupy a bounded worker until the platform returns; terminating a process
still depends on the platform's destroy implementation.
