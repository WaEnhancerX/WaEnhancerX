package com.waenhancer.core.transcription.local

import android.content.Context
import com.waenhancer.xposed.utils.RootCommandRunner
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads the on-device transcription native libraries (whisper.cpp / ggml / parakeet,
 * plus the NDK's libc++_shared.so they all need) on demand instead of bundling them in the
 * APK, and places them into WAEX's own `nativeLibraryDir` so [WhisperNative] can
 * `System.loadLibrary`/`dlopen` them normally.
 *
 * Each file is hosted individually under [BASE_URL], alongside a `manifest.csv` listing
 * every file's size and SHA-256 (same four-column format scripts/build-transcription-libs.sh
 * prints: `file,size_bytes,md5,sha256`). The manifest is fetched fresh every time, not pinned
 * in app code: publishing an updated library is just replacing its file and `manifest.csv`
 * entry at that URL, no app rebuild required. This means integrity checking here only
 * catches a corrupted/incomplete transfer, not a compromised host — [BASE_URL] is the
 * project's own domain, not a third-party mirror.
 *
 * Downloading never starts implicitly: a screen must call [startProvisioning] from an
 * explicit user action (a "Download" button) and poll [progress] to show it, the same
 * pattern [LocalModelStore] already uses for whisper model downloads. [WhisperNative] only
 * ever calls [isProvisioned], never [startProvisioning] — it must not trigger a multi-MB
 * download by itself just because something checked whether transcription is available.
 *
 * Why root is required: since Android 10, an app's own private data directories are mounted
 * `noexec`, so a plain download-then-`System.load()` from `context.filesDir`/`cacheDir` is
 * blocked by the kernel regardless of file permissions. The app's real `nativeLibraryDir` is
 * not `noexec` (that is where its bundled libraries already live), but only PackageManager
 * can normally place files there. With root, this copies the verified files directly into
 * that directory instead, matching the SELinux file context of an already-bundled sibling
 * library (`libwaexdiag.so`) so the dynamic linker treats them the same way.
 *
 * [ROOT_CACHE_DIR] under `/data/adb/` is not touched by app install/update/uninstall, so
 * verified downloads survive those; only `nativeLibraryDir` itself gets wiped on update
 * (PackageManager re-derives it from the installed APK), in which case this re-copies from
 * the cache instead of re-downloading. [ROOT_CACHE_MANIFEST] records the manifest entries
 * that were last verified into the cache, so a later run only re-downloads files whose
 * published SHA-256 actually changed.
 */
object NativeLibsProvisioner {

    private const val BASE_URL = "https://res.waex.mubashar.dev/transcription-libs/"
    private const val MANIFEST_URL = "${BASE_URL}manifest.csv"

    private const val ROOT_CACHE_DIR = "/data/adb/waex/native_cache"
    private const val ROOT_CACHE_MANIFEST = "$ROOT_CACHE_DIR/manifest.csv"

    /** Reference library already bundled for every ABI; its SELinux context is copied onto downloaded libraries. */
    private const val CONTEXT_REFERENCE_LIB = "libwaexdiag.so"

    val REQUIRED_FILES: List<String> = listOf(
        "libwaexwhisper.so",
        "libwhisper.so",
        "libggml.so",
        "libggml-base.so",
        "libggml-cpu-android_armv8.0_1.so",
        "libggml-cpu-android_armv8.2_1.so",
        "libggml-cpu-android_armv8.2_2.so",
        "libggml-cpu-android_armv8.6_1.so",
        "libggml-cpu-android_armv9.0_1.so",
        "libggml-cpu-android_armv9.2_1.so",
        "libggml-cpu-android_armv9.2_2.so",
        "libparakeet.so",
        // NDK-provided C++ runtime; libwhisper.so/libggml*.so/libparakeet.so all need it and
        // it is not guaranteed to exist as a system library on-device.
        "libc++_shared.so",
    )

    private data class ManifestEntry(val name: String, val sizeBytes: Long, val sha256: String)

    enum class Stage { CHECKING, DOWNLOADING, VERIFYING, INSTALLING }

    /** Polled state of the current/last provisioning attempt; {@code error} is set when it failed. */
    data class Progress(
        val stage: Stage,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val running: Boolean,
        val error: String? = null
    )

    @Volatile
    private var currentProgress: Progress? = null

    private val WORKER = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WAEX-NativeLibsProvision").apply { isDaemon = true }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    /** No-root, no-network check: are the libraries already where [WhisperNative] can load them from? */
    @JvmStatic
    fun isProvisioned(context: Context): Boolean {
        val dir = context.applicationInfo.nativeLibraryDir ?: return false
        return REQUIRED_FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0L } }
    }

    /** Last/current attempt's state; null before the first [startProvisioning] call this process. */
    @JvmStatic
    fun progress(): Progress? = currentProgress

    /** Starts provisioning on a background thread unless one is already running. Poll [progress]. */
    @JvmStatic
    fun startProvisioning(context: Context) {
        val existing = currentProgress
        if (existing != null && existing.running) return
        val appContext = context.applicationContext ?: context
        currentProgress = Progress(Stage.CHECKING, 0, 0, running = true)
        WORKER.execute {
            val success = try {
                runProvisioning(appContext)
            } catch (t: Throwable) {
                currentProgress = Progress(Stage.INSTALLING, 0, 0, running = false,
                    error = "Unexpected error (${t.javaClass.simpleName})")
                false
            }
            if (success) {
                val total = currentProgress?.totalBytes ?: 0L
                currentProgress = Progress(Stage.INSTALLING, total, total, running = false)
            } else if (currentProgress?.running != false) {
                currentProgress = Progress(Stage.INSTALLING, 0, 0, running = false,
                    error = "Could not install the transcription libraries (root required)")
            }
        }
    }

    private fun runProvisioning(context: Context): Boolean {
        if (isProvisioned(context)) return true
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir ?: return false

        currentProgress = Progress(Stage.CHECKING, 0, 0, running = true)
        val manifest = fetchManifest() ?: return false
        val byName = manifest.associateBy { it.name }
        val missing = REQUIRED_FILES.filter { it !in byName }
        if (missing.isNotEmpty()) {
            currentProgress = Progress(Stage.CHECKING, 0, 0, running = false,
                error = "Manifest is missing: ${missing.joinToString(", ")}")
            return false
        }
        val totalBytes = REQUIRED_FILES.sumOf { byName.getValue(it).sizeBytes }

        val cachedHashes = readCachedManifest()
        val needsDownload = REQUIRED_FILES.filter { cachedHashes[it] != byName.getValue(it).sha256 }

        val stagingDir = File(context.cacheDir, "waex_native_libs_staging_${System.nanoTime()}")
        stagingDir.mkdirs()
        try {
            var downloadedSoFar = totalBytes - needsDownload.sumOf { byName.getValue(it).sizeBytes }
            currentProgress = Progress(Stage.DOWNLOADING, downloadedSoFar, totalBytes, running = true)
            for (name in needsDownload) {
                val entry = byName.getValue(name)
                val base = downloadedSoFar
                val ok = downloadFile(entry, File(stagingDir, name)) { partial ->
                    currentProgress = Progress(Stage.DOWNLOADING, base + partial, totalBytes, running = true)
                }
                if (!ok) return false
                downloadedSoFar += entry.sizeBytes
                currentProgress = Progress(Stage.DOWNLOADING, downloadedSoFar, totalBytes, running = true)
            }

            currentProgress = Progress(Stage.INSTALLING, totalBytes, totalBytes, running = true)
            val installed = installAndRefreshCache(stagingDir, needsDownload, byName, nativeLibraryDir)
            if (!installed) return false
            return isProvisioned(context)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** `name,sha256` pairs last verified into the root cache; empty when the cache is fresh/unreadable. */
    private fun readCachedManifest(): Map<String, String> {
        val out = RootCommandRunner.run("cat ${shellQuote(ROOT_CACHE_MANIFEST)} 2>/dev/null", 4_000L) ?: return emptyMap()
        return out.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size >= 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun fetchManifest(): List<ManifestEntry>? {
        currentProgress = Progress(Stage.CHECKING, 0, 0, running = true)
        val request = try {
            Request.Builder().url(MANIFEST_URL).build().also {
                require(it.url.scheme == "https") { "Must use HTTPS" }
            }
        } catch (_: Exception) {
            currentProgress = Progress(Stage.CHECKING, 0, 0, running = false, error = "Invalid manifest URL")
            return null
        }
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.request.url.scheme != "https") {
                    currentProgress = Progress(Stage.CHECKING, 0, 0, running = false,
                        error = "Could not fetch manifest (HTTP ${response.code})")
                    return null
                }
                val text = response.body?.string().orEmpty()
                val lines = text.lineSequence().drop(1) // header: file,size_bytes,md5,sha256
                val entries = lines.mapNotNull { line ->
                    val parts = line.split(',')
                    if (parts.size < 4) return@mapNotNull null
                    val size = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
                    ManifestEntry(parts[0].trim(), size, parts[3].trim().lowercase())
                }.toList()
                if (entries.isEmpty()) {
                    currentProgress = Progress(Stage.CHECKING, 0, 0, running = false, error = "Manifest is empty or malformed")
                    null
                } else entries
            }
        } catch (e: Exception) {
            currentProgress = Progress(Stage.CHECKING, 0, 0, running = false,
                error = e.message ?: "Could not fetch manifest (${e.javaClass.simpleName})")
            null
        }
    }

    private fun downloadFile(entry: ManifestEntry, destination: File, onProgress: (Long) -> Unit): Boolean {
        val request = try {
            Request.Builder().url("$BASE_URL${entry.name}").build().also {
                require(it.url.scheme == "https") { "Must use HTTPS" }
            }
        } catch (_: Exception) {
            currentProgress = Progress(Stage.DOWNLOADING, 0, 0, running = false, error = "Invalid download URL for ${entry.name}")
            return false
        }
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.request.url.scheme != "https") {
                    currentProgress = Progress(Stage.DOWNLOADING, 0, 0, running = false,
                        error = "Download of ${entry.name} failed (HTTP ${response.code})")
                    return false
                }
                val body = response.body ?: run {
                    currentProgress = Progress(Stage.DOWNLOADING, 0, 0, running = false, error = "Empty download: ${entry.name}")
                    return false
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            onProgress(read)
                        }
                    }
                }
                currentProgress = Progress(Stage.VERIFYING, read, entry.sizeBytes, running = true)
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (read != entry.sizeBytes || !actual.equals(entry.sha256, ignoreCase = true)) {
                    destination.delete()
                    currentProgress = Progress(Stage.VERIFYING, read, entry.sizeBytes, running = false,
                        error = "${entry.name} failed its integrity check")
                    return false
                }
                true
            }
        } catch (e: Exception) {
            destination.delete()
            currentProgress = Progress(Stage.DOWNLOADING, 0, 0, running = false,
                error = e.message ?: "Download of ${entry.name} failed (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Copies newly-downloaded files from [stagingDir] and the rest from the root cache into
     * both the cache (refreshing [ROOT_CACHE_MANIFEST]) and [nativeLibraryDir].
     */
    private fun installAndRefreshCache(
        stagingDir: File,
        downloadedNames: List<String>,
        byName: Map<String, ManifestEntry>,
        nativeLibraryDir: String
    ): Boolean {
        val downloaded = downloadedNames.toSet()
        val cacheScript = StringBuilder("mkdir -p ${shellQuote(ROOT_CACHE_DIR)} 2>/dev/null; ")
        val installScript = StringBuilder()
        val manifestLines = StringBuilder()
        for (name in REQUIRED_FILES) {
            val entry = byName.getValue(name)
            val cacheDst = shellQuote("$ROOT_CACHE_DIR/$name")
            val liveDst = shellQuote("$nativeLibraryDir/$name")
            if (name in downloaded) {
                val src = shellQuote(File(stagingDir, name).absolutePath)
                cacheScript.append("cp -f $src $cacheDst 2>/dev/null && chmod 644 $cacheDst 2>/dev/null; ")
            }
            installScript.append(
                "cp -f $cacheDst $liveDst 2>/dev/null && chmod 644 $liveDst 2>/dev/null && " +
                    "chcon --reference=${shellQuote("$nativeLibraryDir/$CONTEXT_REFERENCE_LIB")} $liveDst 2>/dev/null; "
            )
            manifestLines.append("$name,${entry.sha256}\n")
        }
        val writeManifest = "cat > ${shellQuote(ROOT_CACHE_MANIFEST)} <<'WAEX_EOF' 2>/dev/null\n$manifestLines" + "WAEX_EOF\n"
        val script = "$cacheScript$writeManifest$installScript echo WAEX_NATIVE_INSTALL_DONE"
        val out = RootCommandRunner.run(script, 20_000L)
        if (out?.contains("WAEX_NATIVE_INSTALL_DONE") != true) {
            currentProgress = Progress(Stage.INSTALLING, 0, 0, running = false,
                error = "Could not copy libraries into place (root required)")
            return false
        }
        return true
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
