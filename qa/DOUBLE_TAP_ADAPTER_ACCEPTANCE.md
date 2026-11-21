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
remain without a reaction target. The original touch listener still receives
each event and determines the returned handled value.

The host message/action selectors and reaction sender contract are unchanged.

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

Eleven scenarios cover a first adapter, two distinct instances of the same
adapter class, duplicate registration, a second chat after two installations,
null/failed/unsupported recycled items, recycling while disabled, the original
touch return value, retry after a registration failure and a gesture interrupted
by row recycling.

## Negative controls

    python3 qa/test_double_tap_adapter_binding.py --source /path/to/v38/app/src/main/java/com/waenhancer/xposed/features/conversation/DoubleTapReactionHook.java --out /tmp/waex-double-tap-v38 --allow-test-failure

Normal CI must omit --allow-test-failure. Both production compilation and
completion of all 11 scenarios are required for a negative control.

The candidate passed 11/11 scenarios. V38 compiled and finished the same fixture
with seven failures. Four compiling mutations were detected: removing Method
deduplication (one failure), retaining the recycled message target (four),
retaining a failed registration reservation (one), and restoring the captured
installer adapter (two).

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
