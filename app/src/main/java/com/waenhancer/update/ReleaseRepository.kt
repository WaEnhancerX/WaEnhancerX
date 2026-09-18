package com.waenhancer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

enum class ReleaseChannel { STABLE, BETA, BOTH }

data class WaexRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String,
    val downloadUrl: String?,
    val downloadSize: Long,
) {
    val version: String get() = normalizeVersion(tagName.removePrefix("debug-"))
    val isBeta: Boolean get() = version.contains("-beta-")
}

object ReleaseRepository {
    const val RELEASES_API = "https://waex.mubashar.dev/api/releases"
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+(-beta-\\d+)?$")
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()

    suspend fun fetchReleases(): List<WaexRelease> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "WaEnhancer X")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(if (response.code == 403 || response.code == 429) "Rate limit exceeded. Please try again later." else "Release service returned ${response.code}.")
            val array = JSONArray(response.body?.string() ?: error("Release service returned an empty response."))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val tag = item.optString("tag_name").trim()
                    val version = normalizeVersion(tag.removePrefix("debug-"))
                    if (!versionPattern.matches(version)) continue
                    val assets = item.optJSONArray("assets")
                    var apkUrl: String? = null
                    var apkSize = 0L
                    if (assets != null) for (j in 0 until assets.length()) {
                        val asset = assets.optJSONObject(j) ?: continue
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url").takeIf(String::isNotBlank)
                            apkSize = asset.optLong("size")
                            break
                        }
                    }
                    add(WaexRelease(tag, item.optString("name", tag), item.optString("body", "No changelog available."), item.optString("published_at"), item.optString("html_url"), apkUrl, apkSize))
                }
            }.sortedByDescending { versionCode(it.version) }
        }
    }

    suspend fun fetchRelease(tagName: String): WaexRelease? = fetchReleases().firstOrNull {
        it.tagName.equals(tagName, true) || it.name.equals(tagName, true) || it.tagName.endsWith(tagName, true)
    }

    fun findUpdate(releases: List<WaexRelease>, installedVersion: String, channel: ReleaseChannel): WaexRelease? {
        val installed = versionCode(normalizeVersion(installedVersion))
        return releases.asSequence()
            .filter { channel == ReleaseChannel.BOTH || (channel == ReleaseChannel.BETA) == it.isBeta }
            .filter { versionCode(it.version) > installed }
            .maxByOrNull { versionCode(it.version) }
    }

    fun versionCode(version: String): Long {
        val normalized = normalizeVersion(version)
        val betaIndex = normalized.indexOf("-beta-")
        val base = if (betaIndex > 0) normalized.substring(0, betaIndex) else normalized
        val parts = base.split('.')
        val major = parts.getOrNull(0)?.toLongOrNull() ?: return 0
        val minor = parts.getOrNull(1)?.toLongOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toLongOrNull() ?: 0
        val baseCode = major * 1_000_000L + minor * 1_000L + patch
        if (betaIndex < 0) return baseCode * 1_000L + 999L
        val beta = normalized.substring(betaIndex + 6).toLongOrNull()?.coerceIn(1, 998) ?: 1
        return baseCode * 1_000L + beta
    }
}

fun normalizeVersion(value: String): String = value.trim().removePrefix("v").removePrefix("V").substringBefore('+').trim()
