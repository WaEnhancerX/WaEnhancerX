# Image-quality MobileConfig: screenshot variant

## Source-backed contract

When the WAEX **imagequality** preference is enabled, the early MobileConfig
registry must consistently cover the default and screenshot-specific
`ProcessImageQuality` value families. Both objects use the same constructor
slots: `maxKb`, `quality`, `maxEdge`.

An **installed personal WhatsApp 2.26.43.76** APK
(SHA-256 `a1b3b262a86c8ed928bce8b777da69a2324607356bd03d814396a66bbc27da55`)
was inspected **read-only** through an isolated JADX 1.5.6 extraction of
selected DEX files. The relevant constructors were observed as:

| Variant (obfuscated class in this APK) | maxKb ABProp | quality ABProp | maxEdge ABProp |
| --- | ---: | ---: | ---: |
| Default `X.9P3` (`X.PS8` subclass) | 1577 | 1578 | 1576 |
| Screenshot `X.J2X` (`X.PS8` subclass) | **2477** | **2475** | **2476** |

In both cases the constructor calls `X.PS8(int, int, int)`, which assigns
`A02=maxKb`, `A03=quality`, `A01=maxEdge` and exposes these meanings through
its `toString()`. These IDs describe **this exact WhatsApp revision**; they
are not a claim of stable names/IDs across all host versions. The broader
JADX decompilation did not complete within the 900-second diagnostic window;
the three named classes themselves were read completely. No user chats or
numbers were included in the evidence.

WAEX already overrides the default IDs, but not the screenshot trio. Adding
the trio to the existing enabled/disabled property arrays reuses the same
limits: 50 MiB (`51200` KiB), quality `100`, max edge `6000`. Existing
custom user ABProps are loaded **after** these defaults by
`FeatureRegistry.applyUiPreHooks`, preserving their explicit priority.
The existing constructor hook targets the base `ProcessImageQuality` class
separately; this change complements the early property path and does not
claim to fix constructor interception or guarantee a delivered HD upload.

## Executable source contract

```bash
python3 qa/test_media_quality_screenshot_props.py \
  --source-root <checkout> --out <new-output-directory>
```

The runner extracts the complete **unmodified** declarative constants and
`configurePropertyOverrides(boolean)` method from the selected production
source, compiles that fragment with `javac --release 17`, and executes an
independent contract fixture. The explicit registry double preserves the
actual `PropsOverrideHook` set/remove map semantics, rejects invalid IDs,
and makes unexpected registered IDs visible. It does **not** simulate a
successful DexKit lookup, Xposed callback, installed APK, real media encoding,
or WhatsApp's full MobileConfig resolver.

The fixture requires both ID families to map the correct values; checks
other existing IDs, five booleans, unrelated entries, disabled cleanup,
re-enable, and custom override precedence. The expected screenshot numbers
come from the APK constructor, **not** from parsing the arrays under test.
It records exact input SHA-256, extracted compilation bytes, JDK return codes,
toolchain/version, duration, individual failed assertions and aggregate results.

Run it first against the unchanged base to preserve a negative control:
the missing screenshot IDs must cause specific assertion failures while
other existing property groups still work. Then run against the patch and
confirm zero failures using the same JDK. Do not count baseline assertion
failures as a successful baseline feature result.

On isolated SentinelX Windows 11 / Temurin JDK 17.0.20.1, the verified source
base `a0b047ae832546de1ba4fc8f8e6d21cf54750813` compiled and ran all
**59 assertions with nine failures**: the missing three screenshot values,
unexpected registered-ID set, one stale custom value on disabling, and
re-enable/reconciliation failures. The identical Java fixture against the
four-file candidate passed **59/59**. The candidate also passed **59/59** under
JBR 25.0.3. This is JVM policy coverage, not real media/WhatsApp acceptance.

## Real acceptance still pending

1. Verify the enabled/disabled preference is correctly delivered through the
   provider/bridge in the installed WAEX and WhatsApp processes.
2. Verify the actual ABProp getter interception is registered and invoked
   with the screenshot trio, and that the resulting `X.J2X` field values
   match the intended configuration.
3. Verify a genuine screenshot **sender path** consumes that object and
   compare the encoded file metadata and bytes against the unmodified control.
   This experiment must use the user's authorized personal account/contact
   if any message is sent; no communication is required by the JVM fixture.
4. Test unsupported host variants conservatively; no claim for WhatsApp
   Business, untested APKs, battery use, FPS or latency.
