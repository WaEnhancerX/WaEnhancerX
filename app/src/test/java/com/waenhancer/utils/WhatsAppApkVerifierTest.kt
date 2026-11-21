package com.waenhancer.utils

import com.waenhancer.utils.WhatsAppApkVerifier.ApkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppApkVerifierTest {

    private val rules = listOf("2.26.45.xx", "2.26.44.xx", "2.26.43.xx")
    private val pinnedA = "39:87:D0:43:D1:0A:EF:AF:5A:87:10:B3:67:14:18:FE:57:E0:E1:9B:65:3C:9D:F8:25:58:FE:B5:FF:CE:5D:44"
    private val pinnedB = "FB:92:0D:38:1B:EE:1B:20:93:F2:7D:C8:F1:3D:99:4D:A6:29:DC:91:88:7D:05:29:B3:5C:9A:2D:C4:F4:A6:C2"

    private fun info(
        pkg: String? = "com.whatsapp",
        version: String? = "2.26.45.71",
        signers: List<String> = listOf(WhatsAppApkVerifier.normalizeFingerprint(pinnedA))
    ) = ApkInfo(pkg, version, 1L, signers, "ABC")

    @Test
    fun `passes for the right package, a supported version and a pinned signer`() {
        val result = WhatsAppApkVerifier.evaluate("com.whatsapp", info(), rules)
        assertTrue(result.passed)
        assertEquals("2.26.45.xx", result.matchedRule)
    }

    @Test
    fun `any version inside a supported range is allowed, not only the newest`() {
        assertTrue(WhatsAppApkVerifier.evaluate("com.whatsapp", info(version = "2.26.43.5"), rules).passed)
    }

    @Test
    fun `fails for a version outside every range`() {
        val result = WhatsAppApkVerifier.evaluate("com.whatsapp", info(version = "2.24.1.1"), rules)
        assertFalse(result.versionSupported)
        assertNull(result.matchedRule)
        assertFalse(result.passed)
    }

    @Test
    fun `fails for the wrong package`() {
        assertFalse(WhatsAppApkVerifier.evaluate("com.whatsapp", info(pkg = "com.whatsapp.w4b"), rules).passed)
        assertFalse(WhatsAppApkVerifier.evaluate("com.whatsapp", info(pkg = null), rules).passed)
    }

    @Test
    fun `fails closed when the signer is unknown or missing`() {
        assertFalse(WhatsAppApkVerifier.evaluate("com.whatsapp", info(signers = listOf("DEADBEEF")), rules).passed)
        assertFalse(WhatsAppApkVerifier.evaluate("com.whatsapp", info(signers = emptyList()), rules).passed)
    }

    @Test
    fun `every signer must be pinned`() {
        val mixed = listOf(WhatsAppApkVerifier.normalizeFingerprint(pinnedA), "DEADBEEF")
        assertFalse(WhatsAppApkVerifier.evaluate("com.whatsapp", info(signers = mixed), rules).signatureTrusted)
    }

    @Test
    fun `either pinned certificate is accepted and separators do not matter`() {
        val b = WhatsAppApkVerifier.normalizeFingerprint(pinnedB).lowercase()
        assertTrue(WhatsAppApkVerifier.evaluate("com.whatsapp", info(signers = listOf(b)), rules).signatureTrusted)
        assertEquals(WhatsAppApkVerifier.normalizeFingerprint(pinnedA), WhatsAppApkVerifier.normalizeFingerprint(pinnedA.lowercase()))
    }

    @Test
    fun `pinned fingerprints match the ones provided for both packages`() {
        assertEquals(
            setOf(WhatsAppApkVerifier.normalizeFingerprint(pinnedA), WhatsAppApkVerifier.normalizeFingerprint(pinnedB)),
            WhatsAppApkVerifier.PINNED_CERT_SHA256
        )
        assertTrue(WhatsAppApkVerifier.evaluate("com.whatsapp.w4b", info(pkg = "com.whatsapp.w4b"), rules).passed)
    }

    @Test
    fun `splits must share the package and a pinned signer, unreadable splits are skipped`() {
        val good = info()
        val badSigner = info(signers = listOf("DEADBEEF"))
        val wrongPackage = info(pkg = "com.example")
        assertTrue(WhatsAppApkVerifier.evaluate("com.whatsapp", info(), rules, splits = listOf(good, null)).passed)
        assertFalse(WhatsAppApkVerifier.evaluate("com.whatsapp", info(), rules, splits = listOf(good, badSigner)).passed)
        assertFalse(WhatsAppApkVerifier.evaluate("com.whatsapp", info(), rules, splits = listOf(wrongPackage)).splitsTrusted)
        assertEquals(2, WhatsAppApkVerifier.evaluate("com.whatsapp", info(), rules, splits = listOf(good, null)).splitCount)
    }
}
