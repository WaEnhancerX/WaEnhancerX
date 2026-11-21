package com.waenhancer.update

import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Integrity agreement with release metadata, not publisher/certificate authentication. */
internal class UpdateTransferPolicy(expectedSha256: String?, private val expectedSize: Long) {
    companion object {
        const val MAX_BYTES = 300L * 1024 * 1024
        fun parseAssetDigest(value: String?): String? {
            if (value == null) return null
            require(value.matches(Regex("sha256:[a-fA-F0-9]{64}"))) { "Invalid release digest" }
            return value.substring(7).lowercase(Locale.ROOT)
        }
    }
    private val expectedHash = expectedSha256?.also {
        require(it.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256" }
    }
    private val digest = expectedHash?.let { MessageDigest.getInstance("SHA-256") }
    var bytes: Long = 0; private set
    init { require(expectedSize in 0..MAX_BYTES) { "Invalid asset size" } }
    fun append(buffer: ByteArray, count: Int) {
        require(count in 0..buffer.size)
        if (count.toLong() > MAX_BYTES - bytes) throw IOException("Update exceeds size limit")
        bytes += count
        if (expectedSize > 0 && bytes > expectedSize) throw IOException("Update exceeds selected asset size")
        digest?.update(buffer, 0, count)
    }
    fun finish(contentLength: Long) {
        if (expectedSize > 0 && bytes != expectedSize) throw IOException("Selected asset size mismatch")
        if (contentLength >= 0 && bytes != contentLength) throw IOException("Incomplete update download")
        if (expectedHash != null) {
            val actual = digest!!.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!actual.equals(expectedHash, true)) throw IOException("Update SHA-256 mismatch")
        }
    }
}

/** Time-based producer throttling; completion is emitted only after validation. */
internal class UpdateProgressGate {
    private var last: Long? = null
    fun allow(now: Long): Boolean {
        val previous = last
        if (previous != null && now >= previous && now - previous < 250L) return false
        last = now
        return true
    }
}
