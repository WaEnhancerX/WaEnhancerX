# Custom emoji font download: integrity and recovery contract

## Scope and observed reference (2026-11-19)

`CustomEmojiManager.kt` downloads fonts for WAEX's existing custom-emoji option.
This does not implement the discontinued TISU/theme/wallpaper catalog proposals.

The earlier implementation used the remote JSON `filename` directly in
`File(custom_emojis, filename)` for reads and deletion, trusted a partial download,
used a fixed `<filename>.tmp` staging name, deleted the previously installed font
before checking `renameTo`, and returned success even if renaming failed.

The official `https://waex.mubashar.dev/api/emojis` catalog returned 10 packs on
2026-11-19. All download URLs used the HTTPS host
`emoji-fonts.waex.mubashar.dev`, with no redirect for a checked HEAD request.
Sizes ranged from 719,736 to 87,758,820 bytes. Ranged GETs for each pack
returned HTTP 206 and a `00 01 00 00` TrueType signature; this checked only the
first 12 bytes, not complete font content.

## Candidate behavior

- Retain official filenames containing spaces, parentheses and Unicode, while
  rejecting traversal, absolute paths, separators, device/control characters,
  misleading extensions and oversized names. A canonical-parent check rejects
  paths resolving outside the app-private font directory.
- Accept only HTTPS downloads on the current approved WAEX font hosts, port 443
  or its default, without HTTP redirects or credential-bearing URLs.
- Reject negative/oversized declared sizes; bound every stream to 128 MiB, above
  the largest observed official pack. Check the declared size and HTTP body length
  when available, and require a plausible TrueType/OpenType table directory.
  This is **not** a cryptographic checksum or complete font parser.
- Use unique temporary files, sync downloaded bytes, and call Android
  `android.system.Os.rename` only after validation. No pre-deletion of the
  installed font. Failure leaves the prior font in place, and only the newly
  created staging file is removed. Progress reaches 100% only after promotion.
- Invalid catalog entries are omitted; the remaining valid entries continue to
  load. Invalid direct filenames cannot be used to remove arbitrary app files.

## Reproducible verification

| Test | Input and result | Limit |
| --- | --- | --- |
| JVM contract | `python3 qa/test_emoji_font_download_integrity.py`: **PASS 77 assertions**, JDK 17 on Windows 11 | Platform `Os.rename` replaced by strict host double |
| Negative controls | Unsafe filenames, ignored declared length and broken replacement each rejected: **3/3 PASS** | Synthetic controls, not WhatsApp |
| Java Android API | Production helper compiled with `javac --release 8` and `android-34/android.jar`, then D8 `--min-api 24`: **PASS** | Compilation/DEX, not installation into WAEX |
| Android runtime | `python3 qa/test_emoji_font_android_runtime.py ... --serial emulator-5560`: **PASS 10 checks**, Android 14/API 34, platform `Os.rename` | Isolated `/data/local/tmp` data; no WhatsApp UI or network |
| Source parser | `qa/check_syntax.py --root .../core/emojis --json`: **PASS** 1 Java + 1 Kotlin | Syntax only |
| Kotlin type compile | Kotlin 2.3.20/JDK17, Android API34, OkHttp 4.12.0, Okio 3.6.0, coroutines 1.10.2: **PASS** | Isolated component; no Gradle APK |
| Full WAEX integration | **BLOCKED / NOT_VERIFIED** | Authentic private `:licensing` inputs unavailable; no candidate APK installed |

Android probe DEX SHA-256:
`fa2076713475bc53df28175b51816e7f69544f853e2caacf0a044fcc689e6394`.

For device reproduction, supply existing local tools explicitly:

```powershell
& 'C:\ProgramData\TKMTarget\Runtime\python.exe' qa\test_emoji_font_android_runtime.py `
  --adb 'C:\WAEX-Lab\android\sdk\platform-tools\adb.exe' `
  --java 'C:\WAEX-Lab\tools\jdk17\bin\java.exe' `
  --javac 'C:\WAEX-Lab\tools\jdk17\bin\javac.exe' `
  --android-jar 'C:\WAEX-Lab\android\sdk\platforms\android-34\android.jar' `
  --d8-jar 'C:\WAEX-Lab\android\sdk\build-tools\36.0.0\lib\d8.jar' `
  --serial emulator-5560
```

## Known unrelated QA failures

On the unmodified fork `main@0aaffab`, `qa/test_catalog_source.py` fails 2 of
19 tests because its canonical feature count expects 92 while the tree has 97,
and its Meta AI assertion expects only two resource keys. The same exact two
failures appeared on this candidate. `qa/test_catalog_schema_alias_contract.py`
also fails its count assertion on both baseline and candidate. None of these
were suppressed or reclassified as PASS by this change.

The upstream CI at `main@39bb7ad` also has separate pre-existing regression
failures. No passing hosted CI run is claimed for this unmerged branch.

## Recovery and limits

The code lives in a distinct Git worktree and branch from fork `main@0aaffab`;
the main checkout and installed WAEX/WhatsApp packages were not modified.
Reverting its eventual commit restores the prior implementation. No APK,
release, PR or customer data was published by these tests. Test directories
under `/data/local/tmp/waex-emoji-integrity-*` are unique and removed by the
runner. Real WhatsApp/WAEX interactions, other Android versions, changing CDN
hosts, malformed-but-plausible font payloads and crashes during fsync remain
NOT_VERIFIED.
