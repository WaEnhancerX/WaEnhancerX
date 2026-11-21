package com.waenhancer.core.emojis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CustomEmojiPack(
    val filename: String,
    val size: Long,
    val downloadUrl: String,
    val previewUrl: String? = null
) {
    val displayName: String
        get() = filename.removeSuffix(".ttf")

    val formattedSize: String
        get() = CustomEmojiManager.formatFileSize(size)
}

object CustomEmojiManager {

    private const val API_URL = "https://waex.mubashar.dev/api/emojis"
    private const val CUSTOM_EMOJIS_DIR = "custom_emojis"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    fun getEmojisDirectory(context: Context): File {
        val dir = File(context.filesDir, CUSTOM_EMOJIS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getEmojiFile(context: Context, filename: String): File {
        return try {
            EmojiCloudStorage.checkedFontFile(getEmojisDirectory(context), filename)
        } catch (e: IOException) {
            throw IllegalArgumentException("Invalid emoji font filename", e)
        }
    }

    fun isDownloaded(context: Context, filename: String): Boolean {
        if (!EmojiCloudStorage.isSafeFontName(filename)) return false
        return try {
            val file = getEmojiFile(context, filename)
            file.exists() && file.isFile && file.length() > 0L
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun deleteDownloadedEmoji(context: Context, filename: String): Boolean {
        if (filename.isEmpty()) return true
        if (!EmojiCloudStorage.isSafeFontName(filename)) return false
        return try {
            val file = getEmojiFile(context, filename)
            if (file.exists()) file.delete() else true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    suspend fun fetchCloudEmojis(): Result<List<CustomEmojiPack>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("User-Agent", "WAEX-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val json = JSONObject(body)
                // A corrupt entry must not hide the valid packs that follow it.
                Result.success(EmojiCloudCatalog.parse(json).map { entry ->
                    CustomEmojiPack(entry.filename, entry.size, entry.downloadUrl, entry.previewUrl)
                })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadEmoji(
        context: Context,
        pack: CustomEmojiPack,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = getEmojisDirectory(context)

        try {
            val finalFile = EmojiCloudStorage.checkedFontFile(dir, pack.filename)
            if (!EmojiCloudStorage.isAllowedDeclaredSize(pack.size)
                || !EmojiCloudStorage.isApprovedDownloadUrl(pack.downloadUrl)) {
                throw IOException("Invalid emoji font metadata")
            }
            val request = Request.Builder()
                .url(pack.downloadUrl)
                .header("User-Agent", "WAEX-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(IOException("Empty body"))
                val responseLength = body.contentLength()
                if (responseLength > EmojiCloudStorage.MAX_FONT_BYTES) {
                    throw IOException("Emoji font is larger than the supported download limit")
                }
                val totalBytes = if (pack.size > 0L) pack.size else responseLength
                val tempFile = EmojiCloudStorage.createStageFile(dir)

                try {
                    var downloadedBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var lastProgress = 0
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                if (bytesRead > EmojiCloudStorage.MAX_FONT_BYTES - downloadedBytes) {
                                    throw IOException("Emoji font exceeded the supported download limit")
                                }
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0L) {
                                    val progress = ((downloadedBytes * 100L) / totalBytes)
                                        .toInt().coerceIn(0, 99)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    EmojiCloudStorage.validateDownloadedFile(
                        tempFile, downloadedBytes, pack.size, responseLength)
                    EmojiCloudStorage.replaceCompletedFile(tempFile, finalFile)
                } finally {
                    // A unique stage cannot corrupt a previous font if the network,
                    // validation, or atomic replacement fails.
                    tempFile.delete()
                }
            }

            // Notification failures must not turn a committed font into a failed download.
            runCatching { onProgress(100) }
            Result.success(finalFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getDownloadedFiles(context: Context): List<File> {
        val dir = getEmojisDirectory(context)
        return dir.listFiles { file -> file.isFile && EmojiCloudStorage.isSafeFontName(file.name)
                && file.length() > 0 }
            ?.toList() ?: emptyList()
    }

    fun getTotalDownloadedBytes(context: Context): Long {
        return getDownloadedFiles(context).sumOf { it.length() }
    }

    fun clearAllDownloaded(context: Context): Boolean {
        val dir = getEmojisDirectory(context)
        var allDeleted = true
        dir.listFiles()?.forEach { file ->
            if (file.isFile && !file.delete()) {
                allDeleted = false
            }
        }
        return allDeleted
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}

