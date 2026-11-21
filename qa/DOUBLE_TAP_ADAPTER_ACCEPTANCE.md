# Double-tap reaction: bind the row to the current adapter

## Problem and resulting behavior

DoubleTapReactionHook installed a getView callback that captured the adapter
passed to an earlier ListView.setAdapter call. A method hook also runs for other
instances of that adapter class. Reopening a chat could therefore bind its row
to a message from the earlier adapter. Repeated setAdapter calls registered more
callbacks, and a later callback could overwrite the current row's target.

The hook now reserves each resolved getView Method once, removes that reservation
if registration fails, and reads the ListAdapter in the callback's thisObject.
Before each binding it clears the row's old message and pending tap state,
including while the preference is disabled or getItem fails. Unsupported items
remain without a reaction target. Normal first-tap events keep the original
touch listener's return value. Current nightly deliberately consumes the
second tap of a recognized reaction gesture to avoid opening the host tray;
unbound recycled rows must not consume those events.

## Nightly reconciliation (2026-11-21)

The original production fix is already present in nightly
`9972e49fac9bfb650c25ee31fde0da9425b67175`, through `fadc1b00` and later
`879843e0`. This reconciliation leaves `DoubleTapReactionHook.java` byte-for-byte
identical to that nightly revision. Its newer tag-based state, resolver,
diagnostic, reaction and gesture behavior are retained. The remaining change
repairs the focused regression fixture, which no longer compiled against the
current production APIs; it is not a new runtime fix for those original bugs.

## Executable regression

Run from the repository with Python 3 and JDK 17+:

    python3 qa/test_double_tap_adapter_binding.py

To retain generated fixtures, compilation diagnostics and scenario logs:

    python3 qa/test_double_tap_adapter_binding.py --out /tmp/waex-double-tap-candidate

The fixture compiles the complete production DoubleTapReactionHook.java. It
installs the real production setAdapter/getView callbacks, invokes their before
and after handlers, delivers synthetic touch events through the installed
wrapper and records the exact message object passed to a synthetic reaction
sender. The after-callback double unwinds registrations at equal priority.

Fifteen scenarios cover a first adapter, two distinct instances of the same
adapter class, duplicate registration, a second chat after two installations,
null/failed/unsupported recycled items, recycling while disabled, the original
touch return value, retry after a registration failure and a gesture interrupted
by row recycling. Four current-nightly scenarios additionally cover activity
context before tracker readiness, listener replacement during recycling,
non-accumulating touch wrappers, and host event delivery after clearing a target.
The current suite compiles production `EmojiInputPolicy` and `HostClassNames`
as well as the hook. Host resolution, reaction lookup, resources and diagnostics
remain explicit constrained doubles; they are not integrated host acceptance.

## Negative controls

    python3 qa/test_double_tap_adapter_binding.py --source /path/to/v38/app/src/main/java/com/waenhancer/xposed/features/conversation/DoubleTapReactionHook.java --out /tmp/waex-double-tap-v38 --allow-test-failure

Normal CI must omit --allow-test-failure. Both production compilation and
completion of all current scenarios are required for a negative control.

Historical V39 evidence: the candidate passed 11/11 scenarios. V38 compiled and finished that fixture
with seven failures. Four compiling mutations were detected: removing Method
deduplication (one failure), retaining the recycled message target (four),
retaining a failed registration reservation (one), and restoring the captured
installer adapter (two).

Current nightly reconciliation with Temurin JDK 21.0.13.1+2 and Python 3.13.5
passes all 15 scenarios. Run current compiling mutations with:

    python3 qa/test_double_tap_adapter_binding.py --mutation-control --out /tmp/waex-double-tap-current

All four mutations complete 15 scenarios and fail as intended: duplicate method
hooks (one failure), stale recycled tag (five), failed reservation retained
(one), and captured installer (two). A compiler failure is not accepted as a
successful negative control. No remote CI result is implied by this local run.

## Limits and device acceptance

Android, Xposed, DexKit and the reaction sender use explicit JVM doubles. No
message or reaction is sent to another person or service. The fixture supplies
the synthetic FMessage and sender types at the reflection boundary; it does not
verify their discovery in a real WhatsApp build.

On an authorized test installation, reopen two different conversations that
share an adapter class and check a deliberate double tap on each visible
message. Recycle rows by scrolling, toggle the option off/on, and verify that a
gesture started before recycling does not apply to the new message. Confirm
normal scrolling, long press and the original touch listener still work.
Native hook injection, real layout rendering and end-to-end delivery remain
device acceptance work. No measured scrolling or latency improvement is claimed.
