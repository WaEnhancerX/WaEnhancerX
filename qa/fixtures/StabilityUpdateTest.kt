package com.waenhancer.update

import org.json.JSONArray
import java.io.IOException

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StabilityUpdateTest {
    private fun parse(asset: JSONObject): WaexRelease? {
        val item = JSONObject().put("tag_name", "20300-2.0.3").put("assets", JSONArray().put(asset))
        val parser = ReleaseRepository::class.java.getDeclaredMethod("parseRelease", JSONObject::class.java)
        parser.isAccessible = true
        return parser.invoke(ReleaseRepository, item) as WaexRelease?
    }
    private fun asset() = JSONObject().put("name", "WaEnhancerX-v2.0.3_release.apk")
        .put("browser_download_url", "https://github.com/Xposed-Modules-Repo/com.waenhancer/releases/download/20300-2.0.3/WaEnhancerX-v2.0.3_release.apk")
        .put("size", 8612340L)

    @Test fun realJsonRetainsDigestAndDeclaredSize() {
        val hash = "ABCDEF0123456789".repeat(4)
        val selected = parse(asset().put("digest", "sha256:$hash"))!!.assets.single()
        assertEquals(hash.lowercase(), selected.sha256)
        assertEquals(8612340L, selected.size)
    }
    @Test fun missingOrExplicitNullIsLegacyNotStringNull() {
        assertNull(parse(asset())!!.assets.single().sha256)
        assertNull(parse(asset().put("digest", JSONObject.NULL))!!.assets.single().sha256)
    }
    @Test fun malformedAdvertisedDigestDoesNotDowngradeToUnchecked() {
        for (value in listOf<Any>("", "sha256:abc", "md5:" + "1".repeat(32), 42, JSONObject(), "null")) {
            assertTrue("Malformed digest was admitted: $value", parse(asset().put("digest", value))!!.assets.isEmpty())
        }
    }
    @Test fun unrelatedReleaseUrlRemainsRejected() {
        assertTrue(parse(asset().put("browser_download_url", "https://example.invalid/2.0.3.apk"))!!.assets.isEmpty())
    }
    @Test fun streamingDigestAndTimeGate() {
        val bytes = ByteArray(1_000_000) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val policy = UpdateTransferPolicy(hash, bytes.size.toLong())
        bytes.asList().chunked(32768).forEach { policy.append(it.toByteArray(), it.size) }
        policy.finish(bytes.size.toLong())
        val bad = UpdateTransferPolicy("0".repeat(64), bytes.size.toLong())
        bad.append(bytes, bytes.size)
        assertThrows(IOException::class.java) { bad.finish(bytes.size.toLong()) }
        val gate = UpdateProgressGate()
        assertEquals(9, (0L until 2048L).count { gate.allow(it) })
    }
}
