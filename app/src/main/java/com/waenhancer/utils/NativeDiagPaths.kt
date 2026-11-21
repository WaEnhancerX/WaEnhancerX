package com.waenhancer.utils

/**
 * JNI bridge to `libwaexdiag.so` ([waex_diag.c]): the root-hiding-tool (Magisk DenyList /
 * Hide My Applist) file paths, package names, and shell command fragments that
 * [HmaDiagnostics] and [MagiskDenylistDiagnostics] use live in native code instead of as
 * Kotlin string constants.
 *
 * Only arm64-v8a is built (see the `externalNativeBuild` block in app/build.gradle.kts,
 * shared with the on-device transcription library). Callers must check [available] first;
 * on any other ABI, or if the library fails to load for any reason, the HMA / Magisk
 * DenyList diagnostics are skipped rather than falling back to a Kotlin copy of these
 * constants.
 */
internal object NativeDiagPaths {

    val available: Boolean by lazy {
        try {
            System.loadLibrary("waexdiag")
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun lspConfigDb(): String
    external fun hmaZygiskPath(): String
    external fun knownHmaPackages(): Array<String>
    external fun knownHmaConfigPaths(): Array<String>
    external fun hmaActiveCheckCommand(): String
    external fun lspHmaCheckCommand(): String
    external fun hmaShallowSearchCommand(): String
    external fun hmaConfigGlobCommand(): String
    external fun lspLogGlob(): String
    external fun magiskVersionCommand(): String
    external fun magiskDenylistStatusCommand(): String
    external fun magiskDenylistListCommand(): String
    external fun magiskDenylistRemovePrefix(): String
    external fun magiskDenylistRemoveSuffix(): String
}
