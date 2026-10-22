package com.waenhancer.update

import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import com.waenhancer.utils.AndroidUserScope

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.waenhancer.xposed.utils.RootCommandRunner
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
        .followSslRedirects(false)
        .build()

    /** Reject corrupt, partial, and unrelated packages before offering installation. */
    private fun isValidModuleApk(context: Context, file: File, expectedVersion: String? = null): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return runCatching {
            val validZip = ZipFile(file).use { archive ->
                archive.getEntry("AndroidManifest.xml") != null && archive.getEntry("classes.dex") != null
            }
            if (!validZip) return@runCatching false
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            packageInfo?.packageName == context.packageName &&
                (expectedVersion == null || normalizeVersion(packageInfo.versionName.orEmpty())
                    .equals(normalizeVersion(expectedVersion), ignoreCase = true))
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
        return output?.let { "uid=0" in it } == true
    }

    fun downloadApk(
        context: Context, url: String, expectedVersion: String, callback: DownloadCallback,
        expectedSha256: String? = null, expectedSize: Long = 0L
    ): Call? {
        val request: Request
        val integrity: UpdateTransferPolicy
        try {
            request = Request.Builder().url(url).build()
            require(request.url.scheme == "https") { "Updates must use HTTPS" }
            integrity = UpdateTransferPolicy(expectedSha256, expectedSize)
        } catch (error: Exception) {
            callback.onFailure(IOException("Invalid update request", error))
            return null
        }
        // Same-version assets may be republished. Never trust a filename/version cache.
        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback.onFailure(e)
            }
            override fun onResponse(call: Call, response: Response) {
                var staging: File? = null
                var handedOff = false
                try {
                    response.use {
                        if (call.isCanceled()) return
                        if (response.request.url.scheme != "https") throw IOException("Update redirect left HTTPS")
                        if (!response.isSuccessful) throw IOException("Update service returned HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty update download")
                        val total = body.contentLength()
                        if (total > UpdateTransferPolicy.MAX_BYTES) throw IOException("Update exceeds size limit")
                        if (expectedSize > 0 && total >= 0 && total != expectedSize) throw IOException("Selected asset size mismatch")
                        val file = File.createTempFile("waex_download_", ".apk", context.cacheDir)
                        staging = file
                        val progress = UpdateProgressGate()
                        body.byteStream().use { input ->
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    if (call.isCanceled()) throw IOException("Download cancelled")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    integrity.append(buffer, read)
                                    output.write(buffer, 0, read)
                                    if (progress.allow(SystemClock.elapsedRealtime())) {
                                        callback.onProgress(
                                            if (total > 0) (integrity.bytes * 100 / total).toInt().coerceIn(0, 99) else 0,
                                            integrity.bytes, total
                                        )
                                    }
                                }
                            }
                        }
                        if (call.isCanceled()) return
                        integrity.finish(total)
                        if (!isValidModuleApk(context, file, expectedVersion)) throw IOException("APK does not match selected version")
                        if (call.isCanceled()) return
                        callback.onProgress(100, integrity.bytes, total)
                        if (call.isCanceled()) return
                        // Unique final path: a later request cannot replace these verified bytes.
                        handedOff = true
                        callback.onSuccess(file)
                    }
                } catch (error: Exception) {
                    if (!handedOff && !call.isCanceled()) callback.onFailure(error)
                } finally {
                    if (!handedOff) staging?.delete()
                }
            }
        })
        return call
    }

    fun installApk(context: Context, apkFile: File, expectedVersion: String) {
        val activity = context.findActivity() ?: return
        if (!isValidModuleApk(activity, apkFile, expectedVersion)) {
            Toast.makeText(activity, UiText.get(R.string.waex_ui_apk_does_not_match_selected_version_1_2285cdd0, "APK does not match selected version %1\$s", expectedVersion), Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            Toast.makeText(activity, UiText.get(R.string.waex_ui_please_allow_waenhancer_x_to_install_a_cde29fd6, "Please allow WaEnhancer X to install apps"), Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun installApkWithRoot(context: Context, apkFile: File, expectedVersion: String) {
        val activity = context.findActivity() ?: return
        val userId = try {
            AndroidUserScope.matchingUserId(activity.applicationInfo.uid, Process.myUid())
        } catch (_: RuntimeException) {
            Toast.makeText(activity,
                UiText.get(R.string.waex_ui_unable_to_execute_the_root_installer_798f1547,
                    "Unable to execute the root installer"), Toast.LENGTH_LONG).show()
            return
        }
        if (!isValidModuleApk(activity, apkFile, expectedVersion)) {
            Toast.makeText(activity, UiText.get(R.string.waex_ui_apk_does_not_match_selected_version_1_2285cdd0, "APK does not match selected version %1\$s", expectedVersion), Toast.LENGTH_LONG).show()
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
                    val copyResult = runRootCommand(
                        "cat $source > $target && chmod 644 $target && wc -c < $target"
                    )?.trim().orEmpty()
                    val size = copyResult.toLongOrNull() ?: 0
                    if (size == apkFile.length() && size > 1024) {
                        // Package Manager requires -d when the APK has a lower versionCode.
                        // Keep the explicit installation target in the manager's profile.
                        runRootCommand("pm install -r -d --user $userId $target", 70)
                    }
                    else if (copyResult.isNotEmpty()) {
                        "Failed to copy verified APK: $copyResult"
                    } else {
                        "Failed to copy verified APK to temporary storage"
                    }
                } finally {
                    runRootCommand("rm -f -- ${shellQuote(temporary)}")
                }
            } else "Unable to obtain a private installation temporary file"
            val success = result?.trim()?.startsWith("Success", ignoreCase = true) == true
            activity.runOnUiThread {
                if (success) {
                    Toast.makeText(activity, UiText.get(R.string.waex_ui_installation_successful_restarting_dbd45bac, "Installation successful. Restarting..."), Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 2000)
                } else Toast.makeText(
                    activity,
                    UiText.get(R.string.waex_ui_root_installation_failed_1_s_eb2c4718, "Root installation failed: %1\$s", result?.trim().orEmpty().ifEmpty { UiText.get(R.string.waex_ui_unable_to_execute_the_root_installer_798f1547, "Unable to execute the root installer") }),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /** Drain subprocess output concurrently so timeout still works when su hangs. */
    private fun runRootCommand(command: String, timeoutSeconds: Long = 8): String? =
        RootCommandRunner.run(command, TimeUnit.SECONDS.toMillis(timeoutSeconds))

}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
