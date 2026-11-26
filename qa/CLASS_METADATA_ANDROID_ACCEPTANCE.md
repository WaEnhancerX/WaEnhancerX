# Class metadata compatibility

java.lang.ClassValue is available from Android API 34. The application declares
minSdk 24. Both complete production classes must initialize without requesting
ClassValue, while preserving the previous cache contracts.

## Run the host gate

Use JDK 17 or newer and a complete Kotlin compiler distribution:

    python3 qa/test_class_metadata_android_compat.py --out /tmp/waex-class-metadata --kotlinc /path/to/kotlinc

The runner compiles the complete WaJid, HideReceiptsHook and BaseFeature through
test_receipt_stanza_runtime.py, including its original 101 scenarios and 702
checks. It requires that runner's current resource boundary and production
PrivacyPrefs closure. --stanza-runner selects an explicitly reviewed copy when
testing an isolated candidate. The cached DexKit JAR can be supplied with the
existing WAEX_QA_DEXKIT_JAR variable; the receipt runner verifies its hash.

The additional gate contains six class-availability cases and thirteen cache
cases (6 and 62 checks). The class loader denies only java.lang.ClassValue.
The original classes fail both production initialization cases under that
boundary; the replacement passes. Bootstrap String and ClassValue-present
initialization are controls.

The cache cases check typed and string JIDs, negative lookup publication,
Class identity across loaders, explicitly expired weak values, three controlled
first-publication interleavings, unset message-field retry and latest-valid
field publication. A blocked unrelated method return type forces reflection
to pause before publication. No sleeps or probabilistic garbage collection are
used to obtain the race. Weak keys plus weak metadata values avoid a value to
declaring-Class retention cycle; the negative marker contains no metadata.
Expiration may cause idempotent reflection to run again. A negative method
result stays cached for the lifetime of its Class, matching the previous
ClassValue behavior; missing message fields remain retryable.

## Limits

These are host JVM tests with explicit Android/Xposed service boundaries. They
do not establish ART execution, host class unloading timing, installed-module
compatibility, Binder behavior or real WhatsApp delivery. The official SDK API
contract and baseline/candidate class-loading comparison establish the removed
API dependency. Real isolated Android execution must have a separate receipt.

The original receipt, manual retry and call-privacy oracles remain unchanged.
No CI defaults, Android SDK levels, Gradle configuration or native code change.

Reference: https://developer.android.com/reference/java/lang/ClassValue
