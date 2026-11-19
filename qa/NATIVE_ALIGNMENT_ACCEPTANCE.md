# Native-library alignment acceptance

`audit_native_alignment.py` is a read-only packaging diagnostic for an APK
supplied locally. It inspects every native ELF header and program-header table,
checks native member CRCs, and reports SHA-256 identities. For 64-bit libraries it
checks each `PT_LOAD` alignment and ZIP data offsets
for uncompressed libraries. These are separate checks: a ZIP-aligned library can
still contain ELF segments aligned to only 4 KiB.

```sh
python3 qa/audit_native_alignment.py /path/to/module.apk --json
python3 qa/test_native_alignment.py
```

Exit 0 means these packaging checks passed; 1 reports alignment findings; 2 means
the input was malformed, unsupported, out of bounds, or unreadable. A missing
native directory is reported as zero native libraries checked. The tool parses
32-bit libraries but does not give them a 64-bit compatibility verdict.
Compressed libraries are exempt from the ZIP offset check because that check
concerns direct loading. Stored libraries are checked conservatively for direct
loading; the tool does not decode `extractNativeLibs` from the binary manifest.

The synthetic fixtures cover little/big-endian ELF32/ELF64, malformed ranges and
headers, every load segment, distinct ZIP/ELF failures, native CRC
corruption, duplicate ZIP members, ABI mismatches, and CLI exit codes. Synthetic
ELF containers do not execute native code. Public CI uses these synthetic inputs
only; do not commit APKs, extracted libraries, signing material, or private code.

## Release acceptance

1. Audit the exact final APKs and retain their SHA-256 identities with the report.
2. If a library fails ELF alignment, ask its source owner to update/rebuild that
   dependency with the appropriate NDK/linker settings. ZIP realignment does not
   repair its ELF program headers. A private licensing dependency must be rebuilt
   by its authorized owner; this public-source checker does not replace it.
3. Verify final APK signatures, compiled resource tables, install/upgrade paths,
   and both 4 KiB and 16 KiB device behavior separately, using the original signing
   configuration and authorized private modules. Test whichever feature loads
   each native library, including audio conversion if applicable.

The user-supplied WaEnhancerX 2.0.3 debug/release samples were observed to contain
4 KiB `PT_LOAD` alignment in their 64-bit `libopuscodec.so` files while their ZIP
offsets were 16 KiB aligned. This explains a concrete 16 KiB packaging gap, not
the cause of message sending, status, or release-only feature reports. It does
not establish that a later build contains the same library.

### Public 2.0.9 release baseline (2026-11-19)

The official `WaEnhancerX-v2.0.9_release.apk` (SHA-256
`585da692e90640bc76b4fadc9da0eacb74ee88c377468365f9c86c7a4d995c53`)
passes `zipalign -c -P 16 -v 4`, but this auditor exits **1** on five
64-bit ELF entries: `libopuscodec.so` and `libwaexdiag.so` in both
`arm64-v8a` and `x86_64`, plus `arm64-v8a/libwaexlic.so`. Their
`PT_LOAD` segments use 4096-byte alignment. The other 64-bit packaged
entries pass the alignment checks. The release has 21 packaged native
libraries, including 11 with 64-bit ABIs. The auditor's 18 synthetic
unit tests pass, including 4 KiB negative controls.

The new `waexdiag` target is configured for 16 KiB ELF alignment, but
that change does **not** rebuild or certify the prebuilt `opuscodec` and
private licensing libraries. A fully aligned final APK and feature-level
checks on 16 KiB devices remain necessary before claiming compatibility.

### Hosted transcription libraries (2026-11-19)

The source's on-demand endpoint supplied 13 ARM64 native libraries (9,576,176
bytes in total). All downloaded files matched the sizes, MD5 and SHA-256 values
in the fetched `manifest.csv`, whose SHA-256 was
`8c783072c866cdcba1c95b200e7806ffe2b1468abedfb3dbad2c084f45b5b690`.
This checks transfer integrity against the **same** manifest, not independent
publisher authenticity.

```sh
python3 qa/audit_on_demand_native.py /path/to/downloaded-libs --json
python3 qa/test_audit_on_demand_native.py
```

The standalone auditor returns **1** on 12 of the 13 files because their
ARM64 `PT_LOAD` segments align at 4096 bytes. Only `libc++_shared.so`
reports 16384-byte alignment. Eight synthetic tests pass, including an
expected 4 KiB failure, manifest hash/size mismatch, path traversal,
duplicate filenames and an invalid ELF. Neither the new `waexdiag` flags
nor an APK ZIP realignment repair these external files; the source owner
must rebuild and republish the signed-off transcription asset set, then
verify downloads and actual loading on 16 KiB Android devices.

Primary specification and Android guidance:

- [Android: support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [ELF program headers](https://gabi.xinuos.com/elf/07-pheader.html)
- [AOSP linker page rounding and RELRO protection](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp)

The scope is deliberately limited to `PT_LOAD` and native ZIP alignment.
`GNU_RELRO` protection depends on the surrounding writable segment layout and
page rounding; a raw RELRO end modulus alone is not a sufficient failure test.
Section tables, relocations, symbols and Android linker execution are outside
this checker. A passing result does not authenticate an APK, prove correct
resources, or certify WhatsApp/LSPosed feature compatibility.
