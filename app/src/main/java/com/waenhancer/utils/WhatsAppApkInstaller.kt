package com.waenhancer.utils

import android.content.Context
import android.net.Uri
import com.waenhancer.xposed.utils.RootCommandRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Stages a user-picked APK, APKM or XAPK in app-private storage and installs it with root once it
 * has been verified by [WhatsAppApkVerifier]. Every install, upgrade or downgrade goes through
 * root (`pm install -r -d`); there is no system-installer fallback. Nothing is downloaded here.
 */
object WhatsAppApkInstaller {

    private const val STAGE_DIR = "waex_apk_stage"
    private const val MAX_FILE_BYTES = 450L * 1024L * 1024L
    private const val MAX_BUNDLE_BYTES = 700L * 1024L * 1024L
    private const val MAX_BUNDLE_ENTRIES = 64
    private val TEMP_PATH = Regex("/data/local/tmp/waex\\.[A-Za-z0-9]+")
    private val SAFE_APK_NAME = Regex("[A-Za-z0-9._-]{1,120}\\.apk", RegexOption.IGNORE_CASE)

    /**
     * What the user picked, ready to verify and install.
     *
     * @property container the file as picked (the APK itself, or the APKM/XAPK archive).
     * @property apks every APK to install, [base] included. A plain APK has exactly one.
     */
    data class StagedPackage(val container: File, val base: File, val apks: List<File>) {
        val splits: List<File> get() = apks.filter { it != base }
        val isBundle: Boolean get() = apks.size > 1 || container != base
    }

    /** Copies [uri] into the app cache so the files cannot change between verification and install. */
    @JvmStatic
    fun stage(context: Context, uri: Uri): StagedPackage {
        val dir = File(context.cacheDir, STAGE_DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        val container = File(dir, "${UUID.randomUUID()}.pkg")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open the selected file")
            input.use { source ->
                container.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BUNDLE_BYTES) throw IOException("File is too large to be WhatsApp")
                        sink.write(buffer, 0, read)
                    }
                }
            }
            return unpack(container, File(dir, "unpacked"))
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    /**
     * A plain APK stays as is. An APKM/XAPK archive (a zip holding `.apk` files at its root) has
     * those files extracted into [destDir]. Only safe, flat file names are ever written.
     */
    @JvmStatic
    fun unpack(container: File, destDir: File): StagedPackage {
        val extracted = extractRootApks(container, destDir)
        if (extracted.isEmpty()) {
            if (container.length() > MAX_FILE_BYTES) throw IOException("File is too large to be WhatsApp")
            return StagedPackage(container, container, listOf(container))
        }
        val base = selectBase(extracted, bundleManifestBase(container))
        return StagedPackage(container, base, listOf(base) + extracted.filter { it != base })
    }

    /** Extracts root-level `.apk` entries of a zip; empty if there are none or it is not a zip. */
    @JvmStatic
    fun extractRootApks(container: File, destDir: File): List<File> {
        val zip = try { ZipFile(container) } catch (_: Exception) { return emptyList() }
        zip.use { archive ->
            val entries = archive.entries().asSequence()
                .filter { !it.isDirectory && !it.name.contains('/') && !it.name.contains('\\') }
                .filter { SAFE_APK_NAME.matches(it.name) }
                .toList()
            if (entries.isEmpty()) return emptyList()
            if (entries.size > MAX_BUNDLE_ENTRIES) throw IOException("Too many files in the bundle")
            destDir.mkdirs()
            var total = 0L
            return entries.map { entry ->
                val target = File(destDir, entry.name)
                archive.getInputStream(entry).use { source ->
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BUNDLE_BYTES) throw IOException("Bundle is too large")
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                target
            }
        }
    }

    /** `base.apk`, else the file an XAPK manifest names as base, else the largest APK. */
    @JvmStatic
    fun selectBase(apks: List<File>, manifestBaseName: String?): File =
        apks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
            ?: manifestBaseName?.let { name -> apks.firstOrNull { it.name == name } }
            ?: apks.maxBy { it.length() }

    /** The `file` of the `split_apks` entry whose `id` is `base` in an XAPK `manifest.json`. */
    private fun bundleManifestBase(container: File): String? = runCatching {
        ZipFile(container).use { archive ->
            val entry = archive.getEntry("manifest.json") ?: return null
            if (entry.size > 1024 * 1024) return null
            val text = archive.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            parseManifestBase(text)
        }
    }.getOrNull()

    @JvmStatic
    fun parseManifestBase(manifestJson: String): String? = runCatching {
        val splits = Json.parseToJsonElement(manifestJson).jsonObject["split_apks"] as? JsonArray
        splits?.firstNotNullOfOrNull { item ->
            val obj = item as? JsonObject ?: return@firstNotNullOfOrNull null
            if (obj["id"]?.jsonPrimitive?.contentOrNull == "base") obj["file"]?.jsonPrimitive?.contentOrNull else null
        }
    }.getOrNull()

    /**
     * Installs [apks] with root: `pm install -r -d --user 0` for one APK, `pm install-multiple`
     * for a bundle. `-d` allows moving to an older version. Returns null on success, otherwise a
     * short failure message.
     */
    @JvmStatic
    fun installWithRoot(apks: List<File>): String? {
        if (apks.isEmpty()) return "Nothing to install"
        // mktemp creates the directory exclusively; a predictable world-writable path would allow
        // a symlink or APK-swap attack.
        val temporary = RootCommandRunner.run("mktemp -d /data/local/tmp/waex.XXXXXX", TimeUnit.SECONDS.toMillis(8))
            ?.trim()
            ?.takeIf { TEMP_PATH.matches(it) }
            ?: return "Unable to get root access or a temporary folder"
        return try {
            val copied = mutableListOf<String>()
            apks.forEachIndexed { index, apk ->
                val target = "$temporary/$index.apk"
                val reported = RootCommandRunner.run(
                    "cat ${shellQuote(apk.absolutePath)} > ${shellQuote(target)} && chmod 644 ${shellQuote(target)} && wc -c < ${shellQuote(target)}",
                    TimeUnit.SECONDS.toMillis(60)
                )?.trim().orEmpty()
                val size = reported.toLongOrNull() ?: 0L
                if (size != apk.length() || size <= 1024L) {
                    return "Failed to copy ${apk.name}: ${reported.ifEmpty { "no output" }}"
                }
                copied += shellQuote(target)
            }
            RootCommandRunner.run("chmod 755 ${shellQuote(temporary)}", TimeUnit.SECONDS.toMillis(8))
            val command = if (copied.size == 1) "pm install -r -d --user 0 ${copied[0]}"
            else "pm install-multiple -r -d --user 0 ${copied.joinToString(" ")}"
            val result = RootCommandRunner.run(command, TimeUnit.SECONDS.toMillis(180))?.trim().orEmpty()
            if (result.startsWith("Success", ignoreCase = true)) null else result.ifEmpty { "Install failed" }
        } finally {
            RootCommandRunner.run("rm -rf -- ${shellQuote(temporary)}", TimeUnit.SECONDS.toMillis(8))
        }
    }

    /** Deletes everything staged by [stage]. */
    @JvmStatic
    fun cleanup(context: Context) {
        File(context.cacheDir, STAGE_DIR).deleteRecursively()
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
