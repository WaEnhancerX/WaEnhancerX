package com.waenhancer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.waenhancer.config.PreferenceStores

data class WaexAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String? = null,
) {
    val isDebug: Boolean
        get() = name.contains("debug", ignoreCase = true)

    val buildTypeLabel: String
        get() = when {
            isDebug -> "Debug Build"
            name.contains("release", ignoreCase = true) -> "Release Build"
            else -> "Standard Build"
        }

    val formattedSize: String
        get() {
            if (size <= 0) return ""
            val mb = size / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB", mb)
        }
}

data class WaexRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String,
    val assets: List<WaexAsset> = emptyList(),
    val versionCode: Long = ReleaseRepository.versionCodeFromTag(tagName),
    val commit: String? = null,
) {
    val version: String get() = ReleaseRepository.versionFromTag(tagName)
    val channel: UpdateChannel get() = UpdateChannel.fromVersionName(version)
    val defaultAsset: WaexAsset? get() = assets.firstOrNull { !it.isDebug } ?: assets.firstOrNull()
    val downloadUrl: String? get() = defaultAsset?.downloadUrl
    val downloadSize: Long get() = defaultAsset?.size ?: 0L
}

object ReleaseRepository {
    /** Repositories whose release assets and pages may be trusted. */
    internal val ALLOWED_REPOSITORIES = listOf("WaEnhancerX/WaEnhancerX", "Xposed-Modules-Repo/com.waenhancer")

    /**
     * Repository serving each channel through the GitHub Releases API fallback. Open decision:
     * stable currently stays in the legacy repository until the maintainer confirms where it lives.
     */
    internal val CHANNEL_REPOSITORIES = mapOf(
        UpdateChannel.STABLE to "Xposed-Modules-Repo/com.waenhancer",
        UpdateChannel.BETA to "WaEnhancerX/WaEnhancerX",
        UpdateChannel.NIGHTLY to "WaEnhancerX/WaEnhancerX",
    )
    internal const val INDEX_BASE_URL = "https://raw.githubusercontent.com/WaEnhancerX/WaEnhancerX/release-index/"
    const val RELEASES_URL = "https://github.com/WaEnhancerX/WaEnhancerX/releases"
    private const val CACHE_TTL_MS = 3600_000L
    private const val API_MAX_PAGES = 3
    private const val API_WANTED_MATCHES = 30
    private val versionPattern = Regex("^\\d+\\.\\d+(\\.\\d+)?(-[a-zA-Z0-9.-]+)?$")
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()

    private fun indexUrl(channel: UpdateChannel) = "$INDEX_BASE_URL${channel.id}.json"
    private fun apiUrl(channel: UpdateChannel, page: Int) =
        "https://api.github.com/repos/${CHANNEL_REPOSITORIES.getValue(channel)}/releases?per_page=100&page=$page"
    private fun cacheKey(channel: UpdateChannel) = "releases_json_cache_v2_${channel.id}"
    private fun fetchKey(channel: UpdateChannel) = "releases_last_fetch_v2_${channel.id}"
    private fun etagKey(channel: UpdateChannel) = "releases_etag_v2_${channel.id}"

    /** Parses a Releases API array, optionally keeping only one channel (by tag shape). */
    fun parseReleasesFromJson(json: String, channel: UpdateChannel? = null): List<WaexRelease> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parseRelease(item)?.takeIf { channel == null || it.channel == channel }?.let(::add)
            }
        }.sortedByDescending { it.versionCode }
    }.getOrDefault(emptyList())

    private class CachedChannel(val source: String, val body: String) {
        fun parse(channel: UpdateChannel): List<WaexRelease> = when (source) {
            SOURCE_INDEX -> ReleaseIndex.parse(body, channel).orEmpty()
            else -> parseReleasesFromJson(body, channel)
        }
    }
    private const val SOURCE_INDEX = "index"
    private const val SOURCE_API = "api"

    private fun readCache(store: SharedPreferences?, channel: UpdateChannel): CachedChannel? {
        val raw = store?.getString(cacheKey(channel), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            CachedChannel(o.getString("source"), o.getString("body"))
        }.getOrNull()
    }

    private fun writeCache(store: SharedPreferences?, channel: UpdateChannel, source: String, body: String, etag: String?) {
        store?.edit()
            ?.putString(cacheKey(channel), JSONObject().put("source", source).put("body", body).toString())
            ?.putLong(fetchKey(channel), System.currentTimeMillis())
            ?.apply { if (etag != null) putString(etagKey(channel), etag) else remove(etagKey(channel)) }
            ?.apply()
    }

    fun getCachedReleases(
        context: Context,
        channels: Set<UpdateChannel> = UpdateChannel.entries.toSet(),
    ): List<WaexRelease> {
        val store = PreferenceStores.cacheStore(context, PreferenceStores.GITHUB_API_CACHE)
        return channels.flatMap { readCache(store, it)?.parse(it).orEmpty() }.sortedByDescending { it.versionCode }
    }

    /** Releases of [channels] (default: everything the selected channel may be offered), newest first. */
    suspend fun fetchReleases(
        context: Context? = null,
        forceRefresh: Boolean = false,
        channels: Set<UpdateChannel>? = null,
    ): List<WaexRelease> = withContext(Dispatchers.IO) {
        val wanted = channels ?: context?.let { UpdateChannel.selected(it).eligibleChannels() }
            ?: UpdateChannel.STABLE.eligibleChannels()
        val store = context?.let { PreferenceStores.cacheStore(it, PreferenceStores.GITHUB_API_CACHE) }
        val results = coroutineScope {
            wanted.map { channel -> async { runCatching { loadChannel(store, channel, forceRefresh) } } }.awaitAll()
        }
        val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure != null && results.none { it.isSuccess }) throw failure
        results.flatMap { it.getOrNull().orEmpty() }.sortedByDescending { it.versionCode }
    }

    private sealed interface IndexResult {
        object NotModified : IndexResult
        object Unavailable : IndexResult
        class Fresh(val body: String, val etag: String?, val releases: List<WaexRelease>) : IndexResult
    }

    private fun loadChannel(store: SharedPreferences?, channel: UpdateChannel, force: Boolean): List<WaexRelease> {
        val cache = readCache(store, channel)
        val cachedList = cache?.parse(channel).orEmpty()
        if (!force && cachedList.isNotEmpty() &&
            System.currentTimeMillis() - (store?.getLong(fetchKey(channel), 0L) ?: 0L) < CACHE_TTL_MS) {
            return cachedList
        }

        val etag = if (cache?.source == SOURCE_INDEX && cachedList.isNotEmpty()) store?.getString(etagKey(channel), null) else null
        when (val index = fetchIndex(channel, etag)) {
            IndexResult.NotModified -> {
                store?.edit()?.putLong(fetchKey(channel), System.currentTimeMillis())?.apply()
                return cachedList
            }
            is IndexResult.Fresh -> {
                writeCache(store, channel, SOURCE_INDEX, index.body, index.etag)
                return index.releases
            }
            IndexResult.Unavailable -> Unit
        }

        return try {
            val (body, releases) = fetchViaApi(channel)
            if (releases.isNotEmpty()) writeCache(store, channel, SOURCE_API, body, null)
            if (releases.isEmpty() && cachedList.isNotEmpty()) cachedList else releases
        } catch (e: Exception) {
            if (cachedList.isNotEmpty()) cachedList else throw e
        }
    }

    private fun fetchIndex(channel: UpdateChannel, etag: String?): IndexResult = try {
        val request = Request.Builder().url(indexUrl(channel)).header("User-Agent", "WaEnhancer X")
            .apply { if (etag != null) header("If-None-Match", etag) }.build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 && etag != null -> IndexResult.NotModified
                !response.isSuccessful -> IndexResult.Unavailable
                else -> {
                    val source = response.body?.source() ?: return@use IndexResult.Unavailable
                    if (source.request(ReleaseIndex.MAX_BYTES.toLong() + 1)) return@use IndexResult.Unavailable
                    val body = source.buffer.readUtf8()
                    val releases = ReleaseIndex.parse(body, channel)
                    if (releases.isNullOrEmpty()) IndexResult.Unavailable
                    else IndexResult.Fresh(body, response.header("ETag"), releases)
                }
            }
        }
    } catch (_: Exception) {
        IndexResult.Unavailable
    }

    /** Releases API fallback: pages are filtered by tag shape so other channels never leak in. */
    private fun fetchViaApi(channel: UpdateChannel): Pair<String, List<WaexRelease>> {
        val matched = JSONArray()
        var count = 0
        for (page in 1..API_MAX_PAGES) {
            val request = Request.Builder()
                .url(apiUrl(channel, page))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "WaEnhancer X")
                .build()
            val items = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val limited = response.code == 403 || response.code == 429
                    error(if (limited) "Rate limit exceeded. Please try again later." else "Release service returned ${response.code}.")
                }
                JSONArray(response.body?.string() ?: error("Release service returned an empty response."))
            }
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val release = parseRelease(item) ?: continue
                if (release.channel == channel) {
                    matched.put(item)
                    count++
                }
            }
            if (items.length() < 100 || count >= API_WANTED_MATCHES) break
        }
        val body = matched.toString()
        return body to parseReleasesFromJson(body, channel)
    }

    suspend fun fetchRelease(tagName: String, context: Context? = null): WaexRelease? =
        fetchReleases(context = context, channels = UpdateChannel.entries.toSet()).firstOrNull {
            it.tagName.equals(tagName, true) || it.name.equals(tagName, true) ||
                it.version.equals(normalizeVersion(tagName), true)
        }

    /**
     * Highest release the [selected] channel may be offered whose version code beats the installed
     * build. Defaults to the installed build's own channel.
     */
    fun findUpdate(
        releases: List<WaexRelease>,
        installedVersion: String,
        installedVersionCode: Long = versionCode(installedVersion),
        selected: UpdateChannel = UpdateChannel.fromVersionName(installedVersion),
    ): WaexRelease? {
        val eligible = selected.eligibleChannels()
        return releases.asSequence()
            .filter { it.versionCode > 0 && it.channel in eligible && it.versionCode > installedVersionCode }
            .maxByOrNull { it.versionCode }
    }

    /** Version code of a version name on the channel scale; 0 when the name is invalid. */
    fun versionCode(version: String): Long = UpdateChannel.versionCodeOf(version) ?: 0L

    internal fun versionCodeFromTag(tagName: String): Long = versionCode(versionFromTag(tagName))

    fun versionFromTag(tagName: String): String {
        val tag = tagName.trim().removePrefix("debug-")
        val separator = tag.indexOf('-')
        val versionPart = if (separator > 0 && tag.substring(0, separator).all(Char::isDigit)) {
            tag.substring(separator + 1)
        } else tag
        return normalizeVersion(versionPart)
    }

    internal fun isAllowedAssetUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host.equals("github.com", ignoreCase = true) &&
            ALLOWED_REPOSITORIES.any { url.encodedPath.startsWith("/$it/releases/download/", ignoreCase = true) } &&
            url.encodedPath.endsWith(".apk", ignoreCase = true)
    }

    private fun parseRelease(item: JSONObject): WaexRelease? {
        if (item.optBoolean("draft", false)) return null
        val tag = item.optString("tag_name").trim()
        if (!versionPattern.matches(versionFromTag(tag))) return null
        val code = versionCodeFromTag(tag)
        if (code <= 0L) return null

        val rawAssets = item.optJSONArray("assets")
        val parsedAssets = buildList {
            if (rawAssets != null) {
                for (i in 0 until rawAssets.length()) {
                    val asset = rawAssets.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name").trim()
                    val assetUrl = asset.optString("browser_download_url")
                        .takeIf(::isAllowedAssetUrl)
                    if (assetName.endsWith(".apk", ignoreCase = true) &&
                        assetUrl != null && assetMatchesRelease(assetName, versionFromTag(tag))) {
                        // A missing digest is a legacy asset; a malformed advertised digest is not.
                        val sha256 = try {
                            UpdateTransferPolicy.parseAssetDigest(
                                if (asset.isNull("digest")) null else asset.getString("digest")
                            )
                        } catch (_: Exception) { continue }
                        add(WaexAsset(assetName, assetUrl, asset.optLong("size"), sha256))
                    }
                }
            }
        }

        return WaexRelease(
            tagName = tag,
            name = item.optString("name").trim().ifBlank { tag },
            body = item.optString("body").trim().ifBlank { "No changelog available." },
            publishedAt = item.optString("published_at"),
            htmlUrl = item.optString("html_url").takeIf(::isAllowedReleasePageUrl).orEmpty(),
            assets = parsedAssets,
            versionCode = code,
        )
    }

    internal fun isAllowedReleasePageUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host.equals("github.com", ignoreCase = true) &&
            ALLOWED_REPOSITORIES.any { url.encodedPath.startsWith("/$it/releases/", ignoreCase = true) }
    }

    internal fun assetMatchesRelease(assetName: String, releaseVersion: String): Boolean {
        val match = Regex(
            "(?i)(?:^|[-_])v?(\\d+\\.\\d+(?:\\.\\d+)?(?:-[a-z0-9.-]+)?)(?:\\+[0-9a-f]{7,40})?(?=[_-]|\\.apk$)"
        ).find(assetName) ?: return false
        return normalizeVersion(match.groupValues[1]).equals(
            normalizeVersion(releaseVersion),
            ignoreCase = true
        )
    }
}

fun normalizeVersion(value: String): String = value.trim().removePrefix("v").removePrefix("V").substringBefore('+').trim()
