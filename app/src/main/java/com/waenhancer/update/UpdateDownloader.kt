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
import java.util.concurrent.TimeUnit

object UpdateDownloader {
    interface DownloadCallback {
        fun onProgress(progress: Int, currentBytes: Long, totalBytes: Long)
        fun onSuccess(apkFile: File)
        fun onFailure(error: Exception)
    }

    fun downloadApk(context: Context, url: String, versionName: String, callback: DownloadCallback): Call? {
        val uriName = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        val fileName = uriName?.takeIf { it.endsWith(".apk") }
            ?: "WaEnhancer X_${versionName.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.apk"
        val apkFile = File(context.cacheDir, fileName)
        if (apkFile.exists()) {
            callback.onSuccess(apkFile)
            return null
        }
        val call = OkHttpClient().newCall(Request.Builder().url(url).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback.onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback.onFailure(IOException("Unexpected code $response"))
                        return
                    }
                    val body = response.body ?: run {
                        callback.onFailure(IOException("Empty download response"))
                        return
                    }
                    val temporary = File(context.cacheDir, "$fileName.tmp")
                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(temporary).use { output ->
                                val total = body.contentLength()
                                val buffer = ByteArray(8192)
                                var current = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    current += read
                                    callback.onProgress(if (total > 0) (current * 100 / total).toInt() else 0, current, total)
                                }
                                output.flush()
                            }
                        }
                        if (temporary.renameTo(apkFile)) callback.onSuccess(apkFile)
                        else callback.onFailure(IOException("Failed to rename temporary file"))
                    } catch (e: Exception) {
                        if (!call.isCanceled()) callback.onFailure(e)
                    } finally {
                        if (temporary.exists() && !apkFile.exists()) temporary.delete()
                    }
                }
            }
        })
        return call
    }

    fun installApk(context: Context, apkFile: File) {
        val activity = context.findActivity() ?: return
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
        Thread {
            val temporary = "/data/local/tmp/wa_update.apk"
            runRootCommand("rm -f $temporary")
            runRootCommand("cat \"${apkFile.absolutePath}\" > $temporary && chmod 666 $temporary")
            val bytes = runRootCommand("wc -c < $temporary")?.trim()?.toLongOrNull() ?: 0
            val result = if (bytes > 1000) runRootCommand("pm install -r -d --user 0 $temporary")
                else "Failed to copy APK file to /data/local/tmp. Check root permissions."
            runRootCommand("rm -f $temporary")
            val success = result?.lowercase()?.let { "success" in it || "pkg:" in it } == true
            activity.runOnUiThread {
                if (success) {
                    Toast.makeText(activity, "Installation successful. Restarting...", Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 2000)
                } else Toast.makeText(activity, "Root installation failed: ${result?.trim().orEmpty().ifEmpty { "Unknown error" }}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun runRootCommand(command: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else output.trim()
    }.getOrNull()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
