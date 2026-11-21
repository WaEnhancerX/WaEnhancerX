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

    // ---- release channel index and fallback ----
    private val idxRepo = "https://github.com/WaEnhancerX/WaEnhancerX"
    private val idxSha = "a".repeat(64)

    private fun idxAsset(version: String = "2.0.9-nightly.12", tag: String = "20009012-$version") = JSONObject()
        .put("name", "WaEnhancerX-v${version}_release.apk")
        .put("url", "$idxRepo/releases/download/$tag/WaEnhancerX-v${version}_release.apk")
        .put("size", 48123456L).put("sha256", idxSha)

    private fun idxEntry(version: String = "2.0.9-nightly.12", code: Long = 20009012L, asset: JSONObject = idxAsset(version)) = JSONObject()
        .put("tag", "$code-$version").put("version_name", version).put("version_code", code)
        .put("published_at", "2026-11-19T09:58:11Z").put("html_url", "$idxRepo/releases/tag/$code-$version")
        .put("assets", JSONArray().put(asset)).put("notes", "[Fixes]\n- x")

    private fun idxFile(vararg entries: JSONObject, channel: String = "nightly", schema: Int = 1) = JSONObject()
        .put("schema", schema).put("channel", channel).put("releases", JSONArray(entries.toList())).toString()

    private fun idxParse(json: String, channel: UpdateChannel = UpdateChannel.NIGHTLY) = ReleaseIndex.parse(json, channel)

    @Test fun validFileParsesWithDigestAndCode() {
        val release = idxParse(idxFile(idxEntry()))!!.single()
        assertEquals(20009012L, release.versionCode)
        assertEquals(UpdateChannel.NIGHTLY, release.channel)
        assertEquals(idxSha, release.assets.single().sha256)
        assertEquals("2.0.9-nightly.12", release.version)
    }

    @Test fun unusableFilesReturnNull() {
        assertNull(idxParse(idxFile(idxEntry(), schema = 2)))
        assertNull(idxParse("{not json"))
        assertNull(idxParse(""))
        assertNull(idxParse(idxFile(idxEntry(), channel = "beta")))
        assertNull(idxParse("x".repeat(ReleaseIndex.MAX_BYTES + 1)))
        assertNull(idxParse("""{"schema":1,"channel":"nightly"}"""))
    }

    @Test fun emptyReleaseListIsEmpty() {
        assertTrue(idxParse(idxFile())!!.isEmpty())
    }

    private fun assertDropped(e: JSONObject) = assertTrue(idxParse(idxFile(e))!!.isEmpty())

    @Test fun badEntriesAreDropped() {
        val good = idxAsset()
        assertDropped(idxEntry(asset = JSONObject(good.toString()).put("url", "https://evil.example/a.apk")))
        assertDropped(idxEntry(asset = JSONObject(good.toString()).put("url", good.getString("url").replace("WaEnhancerX/WaEnhancerX", "other/repo"))))
        assertDropped(idxEntry(asset = JSONObject(good.toString()).put("url", good.getString("url").replace("https:", "http:"))))
        assertDropped(idxEntry(asset = JSONObject(good.toString()).put("sha256", "XYZ")))
        assertDropped(idxEntry(asset = JSONObject(good.toString()).put("sha256", "A".repeat(64))))
        assertDropped(idxEntry(asset = JSONObject(good.toString()).put("name", "WaEnhancerX-v2.0.8_release.apk")))
        assertDropped(idxEntry(asset = JSONObject(good.toString()).put("size", 0)))
        assertDropped(idxEntry(code = 20009013L))
        assertDropped(idxEntry(version = "2.0.9-nightly.500", code = 20009500L, asset = idxAsset("2.0.9-nightly.500")))
        assertDropped(idxEntry(version = "2.0.9-beta-3", code = 20009503L, asset = idxAsset("2.0.9-beta-3")))
        assertDropped(JSONObject(idxEntry().toString()).put("tag", "20009012-2.0.9-nightly.99"))
    }

    @Test fun oneBadEntryDoesNotHideGoodOnes() {
        val bad = idxEntry(code = 1L)
        val result = idxParse(idxFile(bad, idxEntry()))!!
        assertEquals(1, result.size)
    }

    @Test fun betaFileAcceptsBetaOnly() {
        val beta = idxEntry("2.0.9-beta-3", 20009503L, idxAsset("2.0.9-beta-3"))
        assertEquals(1, idxParse(idxFile(beta, idxEntry(), channel = "beta"), UpdateChannel.BETA)!!.size)
    }

    @Test
    fun apiFallbackFiltersByTagShapePerChannel() {
        fun item(tag: String, version: String) = JSONObject()
            .put("tag_name", tag).put("name", tag)
            .put("assets", JSONArray().put(JSONObject()
                .put("name", "WaEnhancerX-v${version}_release.apk")
                .put("browser_download_url", "https://github.com/WaEnhancerX/WaEnhancerX/releases/download/$tag/WaEnhancerX-v${version}_release.apk")
                .put("size", 10)))
        val json = JSONArray()
            .put(item("20009012-2.0.9-nightly.12", "2.0.9-nightly.12"))
            .put(item("20009503-2.0.9-beta-3", "2.0.9-beta-3"))
            .put(item("20009999-2.0.9", "2.0.9"))
            .put(item("20009500-2.0.9-nightly.500", "2.0.9-nightly.500"))
            .toString()
        assertEquals(listOf("20009012-2.0.9-nightly.12"), ReleaseRepository.parseReleasesFromJson(json, UpdateChannel.NIGHTLY).map { it.tagName })
        assertEquals(listOf("20009503-2.0.9-beta-3"), ReleaseRepository.parseReleasesFromJson(json, UpdateChannel.BETA).map { it.tagName })
        assertEquals(listOf("20009999-2.0.9"), ReleaseRepository.parseReleasesFromJson(json, UpdateChannel.STABLE).map { it.tagName })
        assertEquals(3, ReleaseRepository.parseReleasesFromJson(json).size)
    }
}
