package com.waenhancer.update

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Per-channel release index (schema 1) published by the release pipeline. The file is untrusted:
 * every field is validated, invalid entries are dropped and nothing here throws.
 */
object ReleaseIndex {
    const val SCHEMA = 1
    const val MAX_BYTES = 1_000_000
    const val MAX_ENTRIES = 50
    private val versionName = Regex("^\\d+\\.\\d+\\.\\d+(-beta-\\d+|-nightly\\.\\d+)?$")
    private val sha256 = Regex("^[0-9a-f]{64}$")
    private val commit = Regex("^[0-9a-f]{7,40}$")

    /**
     * Returns the valid releases of [channel], or null when the file itself is unusable
     * (oversized, malformed, wrong schema, wrong channel). An empty list means a valid file
     * without usable entries.
     */
    fun parse(json: String, channel: UpdateChannel): List<WaexRelease>? {
        if (json.isBlank() || json.length > MAX_BYTES) return null
        return try {
            val root = JSONObject(json)
            if (root.optInt("schema", -1) != SCHEMA) return null
            if (root.has("channel") && root.optString("channel") != channel.id) return null
            val entries = root.optJSONArray("releases") ?: return null
            buildList {
                for (i in 0 until minOf(entries.length(), MAX_ENTRIES)) {
                    val item = entries.optJSONObject(i) ?: continue
                    parseEntry(item, channel)?.let(::add)
                }
            }.sortedByDescending { it.versionCode }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEntry(item: JSONObject, channel: UpdateChannel): WaexRelease? {
        val version = item.optString("version_name")
        if (!versionName.matches(version)) return null
        if (UpdateChannel.fromVersionName(version) != channel) return null
        val computed = UpdateChannel.versionCodeOf(version) ?: return null
        val declared = item.optLong("version_code", -1L)
        if (declared <= 0L || declared != computed) return null
        val tag = item.optString("tag").trim()
        if (tag.isEmpty() || ReleaseRepository.versionFromTag(tag) != version) return null

        val assets = parseAssets(item.optJSONArray("assets"), version)
        if (assets.isEmpty()) return null
        val sha = item.optString("commit").lowercase(Locale.ROOT).takeIf { commit.matches(it) }
        return WaexRelease(
            tagName = tag,
            name = version,
            body = item.optString("notes").trim().ifBlank { "No changelog available." },
            publishedAt = item.optString("published_at"),
            htmlUrl = item.optString("html_url").takeIf(ReleaseRepository::isAllowedReleasePageUrl).orEmpty(),
            assets = assets,
            versionCode = computed,
            commit = sha,
        )
    }

    private fun parseAssets(array: JSONArray?, version: String): List<WaexAsset> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val asset = array.optJSONObject(i) ?: continue
                val name = asset.optString("name").trim()
                val url = asset.optString("url")
                val digest = asset.optString("sha256")
                val size = asset.optLong("size", -1L)
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (!ReleaseRepository.isAllowedAssetUrl(url)) continue
                if (!ReleaseRepository.assetMatchesRelease(name, version)) continue
                if (!sha256.matches(digest)) continue
                if (size <= 0L || size > UpdateTransferPolicy.MAX_BYTES) continue
                add(WaexAsset(name, url, size, digest))
            }
        }
    }
}
