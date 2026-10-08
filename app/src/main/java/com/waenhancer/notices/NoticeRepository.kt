package com.waenhancer.notices

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.waenhancer.core.preferences.WaexPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

object NoticeRepository {

    private const val DEFAULT_NOTICES_URL = "https://waex.mubashar.dev/notices.json"
    private const val PREFS_NAME = "wae_notices"
    private const val KEY_CACHE_JSON = "cache_json"
    private const val KEY_CACHE_ETAG = "cache_etag"
    private const val KEY_CACHE_LAST_MODIFIED = "cache_last_modified"
    private const val KEY_CACHE_FETCHED_AT = "cache_fetched_at"

    private const val KEY_LAST_SHOWN_AT = "last_shown_at"
    private const val KEY_LAST_SHOWN_SIG = "last_shown_sig"

    private val MIN_FETCH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)
    private val SHOW_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getActiveNotice(
        context: Context,
        preferenceManager: WaexPreferenceManager? = null,
        forceFetch: Boolean = false
    ): NoticeItem? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val payload = fetchNoticePayload(context, prefs, forceFetch) ?: return@withContext null

        val appVersionName = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"

        val appVersionCode = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        }.getOrNull() ?: 1

        val channel = if (appVersionName.uppercase(Locale.US).contains("DEV") || appVersionName.uppercase(Locale.US).contains("BETA")) {
            "dev"
        } else {
            "release"
        }

        val commitId = getCommitIdFromVersionName(appVersionName)

        // Prioritize critical/warning severities
        val eligibleNotices = payload.notices
            .filter { it.enabled }
            .filter { matchesTargets(it, channel, appVersionCode, commitId) }
            .sortedByDescending { it.severityRank }

        val selected = eligibleNotices.firstOrNull() ?: return@withContext null

        // Rate-limiting check temporarily bypassed for testing
        // val lastShownAt = prefs.getLong(KEY_LAST_SHOWN_AT, 0L)
        // val lastShownSig = prefs.getString(KEY_LAST_SHOWN_SIG, "") ?: ""
        // val currentSig = "${selected.id}:${selected.revision}"
        // val isNewNotice = currentSig != lastShownSig
        // val isExpired = (System.currentTimeMillis() - lastShownAt) >= SHOW_INTERVAL_MS
        // if (!isNewNotice && !isExpired) {
        //     return@withContext null
        // }

        selected
    }

    fun markNoticeShown(context: Context, notice: NoticeItem) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSig = "${notice.id}:${notice.revision}"
        prefs.edit()
            .putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis())
            .putString(KEY_LAST_SHOWN_SIG, currentSig)
            .apply()
    }

    private fun fetchNoticePayload(
        context: Context,
        prefs: SharedPreferences,
        forceFetch: Boolean
    ): NoticePayload? {
        val lastFetch = prefs.getLong(KEY_CACHE_FETCHED_AT, 0L)
        val cachedJson = prefs.getString(KEY_CACHE_JSON, null)

        val canUseCacheOnly = !forceFetch && cachedJson != null && (System.currentTimeMillis() - lastFetch) < MIN_FETCH_INTERVAL_MS
        if (canUseCacheOnly) {
            return parsePayload(cachedJson)
        }

        val reqBuilder = Request.Builder()
            .url(DEFAULT_NOTICES_URL)
            .header("User-Agent", "WaEnhancerX-App")

        val etag = prefs.getString(KEY_CACHE_ETAG, null)
        val lastModified = prefs.getString(KEY_CACHE_LAST_MODIFIED, null)
        if (!etag.isNullOrBlank()) reqBuilder.header("If-None-Match", etag)
        if (!lastModified.isNullOrBlank()) reqBuilder.header("If-Modified-Since", lastModified)

        return try {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 304 && cachedJson != null) {
                    prefs.edit().putLong(KEY_CACHE_FETCHED_AT, System.currentTimeMillis()).apply()
                    parsePayload(cachedJson)
                } else if (response.isSuccessful && response.body != null) {
                    val bodyStr = response.body!!.string()
                    val newEtag = response.header("ETag")
                    val newLastModified = response.header("Last-Modified")

                    prefs.edit()
                        .putString(KEY_CACHE_JSON, bodyStr)
                        .putLong(KEY_CACHE_FETCHED_AT, System.currentTimeMillis())
                        .putString(KEY_CACHE_ETAG, newEtag)
                        .putString(KEY_CACHE_LAST_MODIFIED, newLastModified)
                        .apply()

                    parsePayload(bodyStr)
                } else {
                    cachedJson?.let { parsePayload(it) }
                }
            }
        } catch (e: Exception) {
            cachedJson?.let { parsePayload(it) }
        }
    }

    private fun parsePayload(json: String?): NoticePayload? {
        if (json.isNullOrBlank()) return null
        return try {
            jsonParser.decodeFromString<NoticePayload>(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun matchesTargets(
        notice: NoticeItem,
        channel: String,
        versionCode: Int,
        commitId: String?
    ): Boolean {
        val targets = notice.targets
        if (targets.channels.isNotEmpty() && !targets.channels.any { it.equals(channel, ignoreCase = true) }) {
            return false
        }
        if (targets.minVersion != null && versionCode < targets.minVersion!!) return false
        if (targets.maxVersion != null && versionCode > targets.maxVersion!!) return false

        if (targets.commitIds.isNotEmpty()) {
            if (commitId == null) return false
            val normalized = commitId.uppercase(Locale.US)
            if (!targets.commitIds.any { it.equals(normalized, ignoreCase = true) }) {
                return false
            }
        }
        return true
    }

    private fun getCommitIdFromVersionName(versionName: String?): String? {
        if (versionName.isNullOrBlank()) return null
        val start = versionName.lastIndexOf('(')
        val end = versionName.lastIndexOf(')')
        if (start >= 0 && end > start + 1) {
            val hash = versionName.substring(start + 1, end).trim()
            if (hash.isNotEmpty() && !"unknown".equals(hash, ignoreCase = true)) {
                return hash
            }
        }
        return null
    }
}
