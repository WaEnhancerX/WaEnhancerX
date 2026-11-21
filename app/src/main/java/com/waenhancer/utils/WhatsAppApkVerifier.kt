package com.waenhancer.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded WhatsApp APK before it is installed: the package must be the expected one,
 * the version must sit inside a built-in supported range, and every signing certificate must be
 * one of WhatsApp's pinned SHA-256 fingerprints. Anything else fails closed.
 */
object WhatsAppApkVerifier {

    /**
     * SHA-256 fingerprints of WhatsApp's release signing certificates. The same two apply to
     * `com.whatsapp` and `com.whatsapp.w4b`. Stored without separators, upper case.
     */
    val PINNED_CERT_SHA256: Set<String> = setOf(
        "3987D043D10AEFAF5A8710B3671418FE57E0E19B653C9DF82558FEB5FFCE5D44",
        "FB920D381BEE1B2093F27DC8F13D994DA629DC91887D0529B35C9A2DC4F4A6C2"
    )

    private const val MIN_APK_BYTES = 1024L * 1024L

    /** Config splits are tiny, so splits are accepted from a few KB. */
    const val MIN_SPLIT_BYTES = 4L * 1024L

    /** What was read from the APK file itself. */
    data class ApkInfo(
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long,
        val signerSha256: List<String>,
        val fileSha256: String
    )

    data class Verification(
        val info: ApkInfo,
        val expectedPackage: String,
        val packageMatches: Boolean,
        val versionSupported: Boolean,
        val matchedRule: String?,
        val signatureTrusted: Boolean,
        /** Number of split APKs delivered with the base (0 for a plain APK). */
        val splitCount: Int = 0,
        /** Every split that could be read has the expected package and a pinned signer. */
        val splitsTrusted: Boolean = true
    ) {
        val passed: Boolean get() = packageMatches && versionSupported && signatureTrusted && splitsTrusted
    }

    /** Strips separators and upper-cases so `39:87:d0…` and `3987D0…` compare equal. */
    @JvmStatic
    fun normalizeFingerprint(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()

    /** Pure decision logic, kept free of Android types so it can be unit tested. */
    @JvmStatic
    fun evaluate(
        expectedPackage: String,
        info: ApkInfo,
        builtinRules: List<String>,
        pinned: Set<String> = PINNED_CERT_SHA256,
        splits: List<ApkInfo?> = emptyList()
    ): Verification {
        val version = info.versionName?.trim()?.removePrefix("v").orEmpty()
        val matchedRule = if (version.isEmpty()) null
        else builtinRules.firstOrNull { UniversalVersionValidator.matches(version, it) }
        val signers = info.signerSha256.map(::normalizeFingerprint)
        val pinnedNormalized = pinned.map(::normalizeFingerprint).toSet()
        return Verification(
            info = info,
            expectedPackage = expectedPackage,
            packageMatches = info.packageName == expectedPackage,
            versionSupported = matchedRule != null,
            matchedRule = matchedRule,
            // Every signer must be pinned; an APK with no readable signer is never trusted.
            signatureTrusted = signers.isNotEmpty() && signers.all { it in pinnedNormalized },
            splitCount = splits.size,
            // A split Android cannot parse (null) is skipped here: the package manager still
            // refuses to install splits signed differently from the verified base.
            splitsTrusted = splits.filterNotNull().all { split ->
                split.packageName == expectedPackage &&
                    split.signerSha256.isNotEmpty() &&
                    split.signerSha256.map(::normalizeFingerprint).all { it in pinnedNormalized }
            }
        )
    }

    /** Reads package, version, signers and SHA-256 from [file]; null if it is not a readable APK. */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun inspect(
        context: Context,
        file: File,
        minBytes: Long = MIN_APK_BYTES,
        fileSha256: String? = null
    ): ApkInfo? {
        if (!file.isFile || file.length() < minBytes) return null
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: return null
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }.orEmpty()
            ApkInfo(
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                signerSha256 = signatures.map { sha256Hex(it.toByteArray()) },
                fileSha256 = fileSha256 ?: sha256Hex(file)
            )
        }.getOrNull()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** SHA-256 of the whole [file]. */
    @JvmStatic
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
