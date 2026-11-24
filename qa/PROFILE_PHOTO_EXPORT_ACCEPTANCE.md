# Profile photo export admission and lifetime

## Current nightly reconciliation ? 2026-11-22

Reference upstream: `WaEnhancerX/WaEnhancerX:nightly` at
`aaf23b5764b1d1c9c06b177f9e09265f429acf05`.
The existing PR #100 at `7e92bf7dae4f9eba37ef094d01295d6e875abd9e`
already adds exact MediaStore publication acknowledgements to both save routes.
This extension preserves that logic and adds current source/JVM coverage and
lifecycle failure handling.

The hook now returns separate states for a missing photo, a failed attempted
save, and success, without changing the existing two-step gallery publication
contract. The async save admission is released if executor submission is
rejected. A bitmap created for View rendering is recycled when Canvas creation
or drawing fails. Neither a failed publication nor a cancelled operation is
reported as a successful export.

Reproduction with the portable JDK 21 and Python 3 on Windows:
`python qa/test_profile_photo_save.py`
`python qa/mutate_profile_photo_save.py`
`python qa/test_profile_photo_source_identity.py`

At the current source revision, the first runner compiles the **actual**
ProfilePhotoDownloadHook and HookErrors, then passes 30 scenarios and 76
behavioral checks, including a one-thousand-click admission probe. Four
separately compiled mutations must fail their targeted behavioral assertions
(without compile or fixture failures). The identity source test passes six cases
and rejects a deliberate own-photo cache fallback. Using this same 30-case
fixture, unchanged nightly compiles but fails 16 cases; the original PR #100
compiles but fails 14. The publication failures in the PR #100 contrast are
from the stricter current UI-message oracle, not an absence of its two
MediaStore count checks.

Scope: JVM source tests with bounded Android/Xposed/DexKit/UI fakes. The
executor is real; the ContentResolver and view rendering are controlled.
No signed WAEX APK, WhatsApp process, work profile, MediaStore device,
permission prompt, playback, latency/FPS or heap measurement is tested.
The current source still performs some View access from its worker; device UI
thread ownership and exact host selector compatibility remain NOT_VERIFIED.

## Historical evidence recorded before the current nightly reconciliation

The following older V38 baseline and results are retained for provenance,
not assertions about this current code/fixture combination:

Run `python3 qa/test_profile_photo_save.py --out /tmp/waex-profile-export`.
The menu checks the live preference and owner Activity, then admits at most one
pending save before copying the displayed bitmap. Capture preserves the existing
40-megapixel ceiling for bitmap-backed images as well as rendered drawables.
Drawable bounds are restored in finally and failed captures release their bitmap.

The worker uses application storage context and a weak Activity reference. A
successful result requires encoding, flush, stream close and exactly one pending
MediaStore row update where scoped storage applies. Failure removes only its
owned row, releases the bitmap and allows the next request. Result presentation
checks the Activity both before scheduling and inside the queued UI callback.

The complete hook passes 23 local scenarios / 65 checks. The same fixture executes
all scenarios on V38 and detects 17 assertion failures. With the first worker
blocked and another 1,000 clicks, the candidate makes one bitmap copy and one row,
compared with 1,001 of each in V38. This measures admission/allocation behavior in
the controlled fixture, not phone RAM usage, FPS or end-to-end save latency.

Android/Xposed/DexKit services are explicit doubles; file IO and the executor are
real. Device acceptance must verify the actual viewer binding, storage permission,
gallery output and Activity lifecycle on the target WhatsApp/Android versions.
