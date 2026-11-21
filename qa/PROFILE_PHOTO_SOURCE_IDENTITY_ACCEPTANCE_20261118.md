# Profile-photo source identity — isolated acceptance, 2026-11-18

## Code identity and priority

- Base: WaEnhancerX/WaEnhancerX main at 0aaffab0270a3d0832bef2895f6457f3792fd74a.
- Prepared fork PR branch: fix/profile-photo-source-identity-20261118, C:\WAEX-Lab\work\profile-photo-source-identity-20261118, DESKTOP-N0OOG51 (Windows 11).
- Upstream historical introduction: 7bbb8e6b64e82588b44403e7c2a6d667721927b6, 2026-11-08. It added files/me.jpg to the profile viewer's direct-file candidates. Older open PR #100 concerns image publication/ownership, not this newly added fallback: https://github.com/WaEnhancerX/WaEnhancerX/pull/100.

A third-party contact viewer with a nonempty JID tries matching per-JID cache candidates, then Activity.getFilesDir()/me.jpg. The file me.jpg belongs to this WhatsApp account; it does not establish identity of the viewed contact. Because the direct-file tier runs before contact-stream, PhotoView, and visible-window bitmap fallbacks, a present account photo can be exported *instead of* the contact image.

Read-only real-device metadata probe (by telegram_lab, 2026-11-18T06:55:49.693Z, Android 14 API34, emulator-5560, root already available): /data/user/0/com.whatsapp/files/me.jpg was nonempty, and the user10 file was absent or zero bytes. The probe did not open an image, collect personal bytes, launch WhatsApp, or export anything. This establishes a reachable precondition in user0, not an observed wrong-gallery output.

## Minimal implementation

Remove only the generic files/me.jpg fallback from the direct-file list. Retain the per-JID .j, .jpg and extensionless candidates, all stream and bitmap tiers, option state, hook lifecycle, gallery code and exception handling.

Accepted behavior: if no file is tied to the viewed JID, direct-file resolution returns null and leaves the subsequent extraction tiers available. A name such as me.jpg, by itself, never authenticates the viewer as the account owner.

## Independent reproducible test

Run from the isolated worktree with C:\WAEX-Lab\tools\jdk17\bin in PATH:

~~~powershell
& 'C:\Program Files\Python315\python.exe' qa/test_profile_photo_source_identity.py
~~~

The Python harness extracts and compiles the actual unmodified production Java *method body*, against strictly declared Activity and ContactNameResolver doubles. Synthetic files have distinct marker bytes; no private WhatsApp files are read. It does NOT compile the entire module.

Six unique cases:
1. Third-party JID, no matching cache, own me.jpg exists: return null.
2. Matching per-JID .jpg and unrelated own me.jpg: choose the JID file.
3. Matching per-JID .j and unrelated own me.jpg: choose the JID file.
4. Empty per-JID cache and unrelated own me.jpg: return null.
5. Missing JID: return null.
6. Another unverified JID with own me.jpg: return null.

| Execution | Result | Interpretation |
|---|---|---|
| Original base 0aaffab… | FAIL expected: first assertion, JVM exit 1 | Demonstrates original code selects foreign source under synthetic preconditions. |
| Candidate source method | PASS: 6 assertions, JVM exit 0 | Enforces narrow file-source contract, not installed behavior. |
| Mutant reinserting me.jpg in original position | FAIL expected, first assertion | The test detects the specific regression. |
| git diff --check | PASS | Whitespace only. |
| Old qa/test_profile_photo_save.py against current main | BLOCKED: failed javac compilation on stale Android/Xposed/DEX stubs and resource names already in base | No product-failure inference; this PR does not alter that unrelated fixture. |
| Real installed WAEX/WhatsApp image selection and gallery result | NOT_VERIFIED | Requires version-bound APK, synthetic contact and actual observable exported bytes. |
| Android 10/11/12/13/15, WhatsApp Business | NOT_VERIFIED | No extrapolation. |

Original worktree Java-file raw SHA-256: 95c44dc3e078168c1b48a7450763724a6633ca9e3fca328139de9da8f2ff980e.
Candidate Java-file raw SHA-256 after final CAS patch: 3763bf4f37fa5f0368d60a26e9509c4e4f471f17d654be642a83ff1134bb096c.
New QA test initial CAS SHA-256: dd06d593d3c588a8c9226ee911d32fad0560bb57cc44136a901e29c5c74439c4.

Python test SOURCE_SHA256 is computed after universal-newline decoding; it differs from the raw Windows bytes and should not be equated with that artifact hash.

## Risk, recovery, and next verification

Removing me.jpg may remove a direct-file fallback for the account's *own* profile when the actual self JID cannot be established. The existing stream and visible-bitmap tiers remain available in source, but their success on a specific host is not proved. Reintroduce a self-photo path only with a verified account/self JID comparison, not a filename-based guess.

Other JID-qualified file candidates and reflective helper paths also depend on the current WhatsApp host and do not prove equivalence to the displayed pixels. The separate duplicate-save option still hashes JIDs and can suppress changed photos; it is intentionally outside this patch. No private-data copy, Android app/setting change, APK publication, or upstream push was performed. This evidence predates fork-branch publication; the PR remains user-created.

For real-device acceptance, use only authorized test data: display known image B for a contact while account me.jpg contains A, save through WAEX and verify gallery bytes represent B, including after process restart and in the correct Android profile. The read-only presence test and JVM doubles are not substitutes. Recovery is a normal revert of this local feature commit or discarding the isolated unpublished worktree.
