package com.waenhancer.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit

object UpdateDownloader {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Reject corrupt, partial, and unrelated packages before offering installation. */
    private fun isValidModuleApk(context: Context, file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return runCatching {
            val validZip = ZipFile(file).use { archive ->
                archive.getEntry("AndroidManifest.xml") != null && archive.getEntry("classes.dex") != null
            }
            if (!validZip) return@runCatching false
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            packageInfo?.packageName == context.packageName
        }.getOrDefault(false)
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    interface DownloadCallback {
        fun onProgress(progress: Int, currentBytes: Long, totalBytes: Long)
        fun onSuccess(apkFile: File)
        fun onFailure(error: Exception)
    }

    fun hasRootAccess(): Boolean {
        val output = runRootCommand("id")
        return output?.let { "uid=0" in it || "root" in it } == true
    }

    fun downloadApk(context: Context, url: String, versionName: String, callback: DownloadCallback): Call? {
        val safeVersion = versionName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val segment = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        val safeSegment = segment?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            ?.takeIf { it.endsWith(".apk", true) && !it.startsWith(".") && it.length <= 120 }
        val fileName = safeSegment ?: "WaEnhancerX_${safeVersion}.apk"
        val apkFile = File(context.cacheDir, fileName)
        if (isValidModuleApk(context, apkFile)) {
            callback.onSuccess(apkFile)
            return null
        }
        apkFile.delete() // Never reuse a truncated or unrelated cached download.
        val request = runCatching { Request.Builder().url(url).build() }.getOrElse {
            callback.onFailure(IOException("Invalid update URL", it))
            return null
        }
        if (request.url.scheme != "https") {
            callback.onFailure(IOException("Updates must be downloaded over HTTPS"))
            return null
        }
        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback.onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    // OkHttp can follow redirects, including an HTTPS -> HTTP redirect.
                    // Never install an update transferred over an insecure final hop.
                    if (response.request.url.scheme != "https") {
                        callback.onFailure(IOException("Update redirect left HTTPS"))
                        return
                    }
                    if (!response.isSuccessful) {
                        callback.onFailure(IOException("Update service returned HTTP ${response.code}"))
                        return
                    }
                    val body = response.body ?: run {
                        callback.onFailure(IOException("Empty update download"))
                        return
                    }
                    if (body.contentLength() > 300L * 1024 * 1024) {
                        callback.onFailure(IOException("Update exceeds size limit"))
                        return
                    }
                    // Unique temp path: concurrent updates cannot overwrite one another.
                    var temporary: File? = null
                    try {
                        val staging = File.createTempFile("waex_download_", ".partial.apk", context.cacheDir)
                        temporary = staging
                        body.byteStream().use { input ->
                            FileOutputStream(staging).use { output ->
                                val total = body.contentLength()
                                val buffer = ByteArray(32768)
                                var current = 0L
                                while (true) {
                                    if (call.isCanceled()) throw IOException("Download cancelled")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    current += read
                                    if (current > 300L * 1024 * 1024) throw IOException("Update exceeds size limit")
                                    output.write(buffer, 0, read)
                                    callback.onProgress(
                                        if (total > 0) (current * 100 / total).toInt().coerceIn(0, 100) else 0,
                                        current, total
                                    )
                                }
                            }
                        }
                        if (!isValidModuleApk(context, staging)) {
                            throw IOException("Downloaded file is not a valid WAEX APK")
                        }
                        if (!staging.renameTo(apkFile)) throw IOException("Unable to save verified update")
                        callback.onSuccess(apkFile)
                    } catch (e: Exception) {
                        if (!call.isCanceled()) callback.onFailure(e)
                    } finally {
                        temporary?.delete()
                    }
                }
            }
        })
        return call
    }

    fun installApk(context: Context, apkFile: File) {
        val activity = context.findActivity() ?: return
        if (!isValidModuleApk(activity, apkFile)) {
            Toast.makeText(activity, "Invalid WAEX update package", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            Toast.makeText(activity, "Please allow WaEnhancer X to install apps", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun installApkWithRoot(context: Context, apkFile: File) {
        val activity = context.findActivity() ?: return
        if (!isValidModuleApk(activity, apkFile)) {
            Toast.makeText(activity, "Invalid WAEX update package", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            // mktemp creates the file exclusively; a predictable world-writable
            // /data/local/tmp path would allow a symlink or APK-swap attack.
            val created = runRootCommand("mktemp /data/local/tmp/waex.XXXXXX")
            val temporary = created?.trim()
                ?.takeIf { it.matches(Regex("/data/local/tmp/waex\\.[A-Za-z0-9]+")) }
            val result = if (temporary != null) {
                try {
                    val target = shellQuote(temporary)
                    val source = shellQuote(apkFile.absolutePath)
                    val size = runRootCommand("cat $source > $target && chmod 644 $target && wc -c < $target")
                        ?.trim()?.toLongOrNull() ?: 0
                    if (size > 1024) runRootCommand("pm install -r --user 0 $target", 70)
                    else "Failed to copy verified APK to temporary storage"
                } finally {
                    runRootCommand("rm -f -- ${shellQuote(temporary)}")
                }
            } else "Unable to obtain a private installation temporary file"
            val success = result?.trim()?.startsWith("Success", ignoreCase = true) == true
            activity.runOnUiThread {
                if (success) {
                    Toast.makeText(activity, "Installation successful. Restarting...", Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 2000)
                } else Toast.makeText(activity, "Root installation failed: ${result?.trim().orEmpty().ifEmpty { "Unknown error" }}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Drain subprocess output concurrently so timeout still works when su hangs. */
    private fun runRootCommand(command: String, timeoutSeconds: Long = 8): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val drainer = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(output) {
                            if (output.length < 8192) output.append(line.take(8192 - output.length)).append('\n')
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching null
        }
        drainer.join(500)
        if (process.exitValue() != 0) return@runCatching null
        synchronized(output) { output.toString().trim() }
    }.getOrNull()

}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
