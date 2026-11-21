package com.waenhancer.utils

import android.content.Context
import android.webkit.CookieManager
import android.webkit.URLUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DownloadStatus { DOWNLOADING, COMPLETED, FAILED }

/**
 * One file the user downloaded through WAEX. [id] names the file in module storage and is how a
 * download is found again later; [version] is the release card it was started from.
 */
data class ApkDownload(
    val id: String,
    val packageName: String,
    val version: String,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val startedAt: Long,
    val error: String? = null
) {
    /** 0..1, or null while the total size is unknown. */
    val progress: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * Downloads release files into the module's private storage (`files/waex_downloads`), outside of
 * any shared folder, and keeps their state so progress can be shown on the release card and a
 * finished file can be installed later. Downloads run in this object's own scope, so they carry
 * on while the user leaves the screen as long as the app process lives.
 */
object ApkDownloads {

    private const val DIR = "waex_downloads"
    private const val INDEX = "downloads.json"
    private const val PROGRESS_STEP_MS = 250L
    private val ALLOWED_EXTENSIONS = setOf("apk", "apkm", "xapk", "apks")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val state = MutableStateFlow<List<ApkDownload>>(emptyList())
    private var loaded = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val downloads: StateFlow<List<ApkDownload>> = state.asStateFlow()

    /** Reads the saved index once. Anything left half-done by a previous run is marked failed. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val index = File(directory(context), INDEX)
        val restored = runCatching {
            val array = JSONArray(index.readText())
            List(array.length()) { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList()).mapNotNull { download ->
            when {
                download.status == DownloadStatus.DOWNLOADING ->
                    download.copy(status = DownloadStatus.FAILED, error = "Interrupted")
                download.status == DownloadStatus.COMPLETED && !fileOf(context, download).isFile -> null
                else -> download
            }
        }
        state.value = restored
    }

    fun fileOf(context: Context, download: ApkDownload): File = File(directory(context), "${download.id}.bin")

    /** The newest download started from the [version] release card of [packageName], if any. */
    fun latestFor(downloads: List<ApkDownload>, packageName: String, version: String): ApkDownload? =
        downloads.filter { it.packageName == packageName && it.version == version }.maxByOrNull { it.startedAt }

    /**
     * Only https files that look like an Android package are accepted, and only from APKMirror or
     * from the Cloudflare R2 storage its download link redirects to (whose file names carry
     * `apkmirror.com`). The installer still checks WhatsApp's signature on whatever arrives.
     */
    fun isAcceptable(url: String, contentDisposition: String?, mimeType: String?): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host.orEmpty()
        val onApkMirror = host == "apkmirror.com" || host.endsWith(".apkmirror.com")
        val onStorage = host.endsWith(".r2.cloudflarestorage.com") && uri.path.orEmpty().contains("apkmirror.com")
        if (!onApkMirror && !onStorage) return false
        val extension = URLUtil.guessFileName(url, contentDisposition, mimeType).substringAfterLast('.', "").lowercase()
        return extension in ALLOWED_EXTENSIONS || mimeType == "application/vnd.android.package-archive"
    }

    /** True when [url] itself is a package file (by its name), as opposed to a page that leads to one. */
    fun isPackageFileUrl(url: String): Boolean {
        if (!isAcceptable(url, null, null)) return false
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path.substringAfterLast('.', "").lowercase() in ALLOWED_EXTENSIONS
    }

    /**
     * Starts downloading [url]. The WebView's cookies, user agent and page are sent along, because
     * the file link only works inside the session that passed the site's checks.
     */
    fun start(
        context: Context,
        packageName: String,
        version: String,
        url: String,
        userAgent: String?,
        referer: String?,
        contentDisposition: String?,
        mimeType: String?
    ): ApkDownload {
        load(context)
        val appContext = context.applicationContext
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val download = ApkDownload(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            version = version,
            fileName = fileName,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 0,
            totalBytes = -1,
            startedAt = System.currentTimeMillis()
        )
        state.update { it + download }
        save(appContext)

        jobs[download.id] = scope.launch {
            val target = fileOf(appContext, download)
            val part = File(target.parentFile, "${download.id}.part")
            try {
                val request = Request.Builder().url(url).apply {
                    userAgent?.let { header("User-Agent", it) }
                    referer?.let { header("Referer", it) }
                    CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    val total = body.contentLength()
                    update(download.id) { it.copy(totalBytes = total) }
                    var written = 0L
                    var lastPublish = 0L
                    part.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val now = System.currentTimeMillis()
                                if (now - lastPublish >= PROGRESS_STEP_MS) {
                                    lastPublish = now
                                    update(download.id) { it.copy(downloadedBytes = written) }
                                }
                            }
                        }
                    }
                    if (!isActive) throw CancellationException()
                    if (total > 0 && written != total) throw IOException("Download incomplete")
                    if (!part.renameTo(target)) throw IOException("Could not save the file")
                    update(download.id) { it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = written, totalBytes = written) }
                }
            } catch (cancelled: CancellationException) {
                part.delete()
                throw cancelled
            } catch (error: Exception) {
                part.delete()
                update(download.id) { it.copy(status = DownloadStatus.FAILED, error = error.message ?: "Download failed") }
            } finally {
                jobs.remove(download.id)
                save(appContext)
            }
        }
        return download
    }

    /** Stops a running download and forgets it. */
    fun cancel(context: Context, id: String) = delete(context, id)

    /** Removes a download and its file. */
    fun delete(context: Context, id: String) {
        val appContext = context.applicationContext
        jobs.remove(id)?.cancel()
        state.value.firstOrNull { it.id == id }?.let { fileOf(appContext, it).delete() }
        state.update { list -> list.filterNot { it.id == id } }
        scope.launch { save(appContext) }
    }

    private fun update(id: String, change: (ApkDownload) -> ApkDownload) {
        state.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    @Synchronized
    private fun save(context: Context) {
        runCatching {
            val array = JSONArray()
            state.value.forEach { array.put(toJson(it)) }
            File(directory(context), INDEX).writeText(array.toString())
        }
    }

    private fun toJson(d: ApkDownload) = JSONObject()
        .put("id", d.id)
        .put("packageName", d.packageName)
        .put("version", d.version)
        .put("fileName", d.fileName)
        .put("status", d.status.name)
        .put("downloadedBytes", d.downloadedBytes)
        .put("totalBytes", d.totalBytes)
        .put("startedAt", d.startedAt)
        .put("error", d.error ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject) = ApkDownload(
        id = o.getString("id"),
        packageName = o.getString("packageName"),
        version = o.getString("version"),
        fileName = o.getString("fileName"),
        status = DownloadStatus.valueOf(o.getString("status")),
        downloadedBytes = o.getLong("downloadedBytes"),
        totalBytes = o.getLong("totalBytes"),
        startedAt = o.getLong("startedAt"),
        error = if (o.isNull("error")) null else o.getString("error")
    )
}
