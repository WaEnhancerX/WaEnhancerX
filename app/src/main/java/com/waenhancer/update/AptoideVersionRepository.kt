package com.waenhancer.update

import android.content.Context
import com.waenhancer.config.PreferenceStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads WhatsApp version names from Aptoide's public API.
 *
 * Only the version *names* are used. Aptoide entries are uploaded by third-party stores, so no
 * file, URL or download from Aptoide is ever used or shown to the user.
 */
object AptoideVersionRepository {

    private const val API = "https://ws75.aptoide.com/api/7/listAppVersions"
    private const val LIMIT = 50
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    private const val KEY_JSON_PREFIX = "versions_json_"
    private const val KEY_FETCHED_PREFIX = "versions_fetched_"
    private const val USER_AGENT = "WAEX-version-suggestion"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Extracts `list[].file.vername` from an Aptoide `listAppVersions` response. */
    fun parseVersionNames(json: String): List<String> = runCatching {
        val list = Json.parseToJsonElement(json).jsonObject["list"] as? JsonArray ?: return emptyList()
        list.mapNotNull { item ->
            ((item as? JsonObject)?.get("file") as? JsonObject)
                ?.get("vername")?.jsonPrimitive?.contentOrNull?.trim()
        }.filter { it.isNotEmpty() }.distinct()
    }.getOrDefault(emptyList())

    /**
     * Version names for [packageName]. Served from cache for 24 h; if the network fails the stale
     * cache is returned, and an empty list when there is none. Never throws.
     */
    suspend fun fetchVersionNames(
        context: Context,
        packageName: String,
        forceRefresh: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        val cache = PreferenceStores.cacheStore(context, PreferenceStores.APTOIDE_API_CACHE)
        val cachedJson = cache.getString(KEY_JSON_PREFIX + packageName, null)
        val cached = cachedJson?.let(::parseVersionNames).orEmpty()
        val fetchedAt = cache.getLong(KEY_FETCHED_PREFIX + packageName, 0L)
        if (!forceRefresh && cached.isNotEmpty() && System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS) {
            return@withContext cached
        }
        runCatching {
            val request = Request.Builder()
                .url("$API/package_name=$packageName/limit=$LIMIT")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use cached
                val body = response.body?.string().orEmpty()
                val names = parseVersionNames(body)
                if (names.isNotEmpty()) {
                    cache.edit()
                        .putString(KEY_JSON_PREFIX + packageName, body)
                        .putLong(KEY_FETCHED_PREFIX + packageName, System.currentTimeMillis())
                        .apply()
                    names
                } else {
                    cached
                }
            }
        }.getOrDefault(cached)
    }
}
