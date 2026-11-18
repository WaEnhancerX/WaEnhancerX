package com.waenhancer.community

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Only metadata and inert media. Community responses cannot install code or modify WhatsApp data.
 * All methods block: callers must dispatch them off the main thread.
 */
class CommunityCatalogClient(
    injectedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = false }
    // Enforce the security invariant even if the caller injects a client that follows redirects.
    private val client = injectedClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun list(
        baseUrl: String,
        sort: CommunitySort = CommunitySort.NEWEST,
        type: CommunityPackType? = null,
        pageToken: String? = null
    ): Result<CommunityPage> = runCatching {
        require(CommunityPackPolicy.validCursor(pageToken)) { "Invalid page token." }
        val builder = apiRoot(baseUrl).newBuilder()
            .addPathSegments("v1/packs")
            .addQueryParameter("sort", sort.wireValue)
            .addQueryParameter("page_size", "20")
        type?.let { builder.addQueryParameter("type", it.wireValue) }
        pageToken?.let { builder.addQueryParameter("pageToken", it) }
        val request = Request.Builder().url(builder.build()).get().header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            ensureOriginalUrl(response, request)
            if (response.code != 200) throw IOException("Catalog returned HTTP ${response.code}.")
            if (response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "application/json")
                throw IOException("Unexpected catalog content type.")
            val body = response.body ?: throw IOException("Empty catalog response.")
            val data = readLimited(body.byteStream(), CommunityPackPolicy.MAX_RESPONSE_BYTES.toLong())
            parsePage(data)
        }
    }

    /**
     * Saves a verified immutable version under app-private files. Does NOT apply it to WhatsApp;
     * a host-specific wallpaper renderer/selector must be independently verified first.
     */
    fun download(baseUrl: String, pack: CommunityPack, destinationRoot: File): Result<File> = runCatching {
        val violations = CommunityPackPolicy.violations(pack)
        require(violations.isEmpty()) { "Invalid pack: ${violations.joinToString()}." }
        val destination = File(destinationRoot, "${pack.id}-${pack.sha256}.${extension(pack.mediaType)}")
        destinationRoot.mkdirs()
        if (!destinationRoot.isDirectory || destinationRoot.canonicalFile != destination.parentFile?.canonicalFile)
            throw IOException("Invalid download directory.")
        if (destination.exists()) {
            if (destination.isFile && destination.length() == pack.sizeBytes && digestFile(destination) == pack.sha256)
                return@runCatching destination
            throw IOException("An existing download does not match the catalog checksum.")
        }
        val url = apiRoot(baseUrl).newBuilder()
            .addPathSegments("v1/packs")
            .addPathSegment(pack.id)
            .addPathSegment("file")
            .build()
        val request = Request.Builder().url(url).get()
            .header("Accept", pack.mediaType)
            .header("Accept-Encoding", "identity")
            .build()
        val temp = File.createTempFile(".waex-community-", ".partial", destinationRoot)
        try {
            client.newCall(request).execute().use { response ->
                ensureOriginalUrl(response, request)
                if (response.code != 200) throw IOException("Download returned HTTP ${response.code}.")
                if (response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != pack.mediaType)
                    throw IOException("Download MIME mismatch.")
                response.header("X-WAEX-SHA256")?.let {
                    if (it != pack.sha256) throw IOException("Server and catalog checksums disagree.")
                }
                response.header("Content-Length")?.toLongOrNull()?.let {
                    if (it != pack.sizeBytes) throw IOException("Download length mismatch.")
                }
                val body = response.body ?: throw IOException("Empty download.")
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            length += n
                            if (length > pack.sizeBytes || length > CommunityPackPolicy.maxContentBytes(pack.type))
                                throw IOException("Download exceeds declared limit.")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (length != pack.sizeBytes || digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } != pack.sha256)
                    throw IOException("Download checksum mismatch.")
                if (!validContent(temp, pack.mediaType)) throw IOException("Invalid downloaded content.")
            }
            if (!temp.renameTo(destination)) throw IOException("Unable to store verified download.")
            destination
        } finally {
            temp.delete()
        }
    }

    private fun apiRoot(raw: String): HttpUrl {
        val uri = CommunityPackPolicy.trustedApiRoot(raw)
            ?: throw IllegalArgumentException("A valid HTTPS catalog address is required.")
        return HttpUrl.Builder()
            .scheme("https")
            .host(uri.host)
            .apply { if (uri.port != -1) port(uri.port) }
            .build()
    }

    private fun ensureOriginalUrl(response: Response, requested: Request) {
        if (response.request.url != requested.url) throw IOException("Catalog response changed the HTTPS origin or URL.")
    }

    private fun parsePage(data: ByteArray): CommunityPage {
        val text = decodeUtf8(data)
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid catalog JSON.")
        if (root.number("schema_version") != 1L) throw IOException("Unsupported catalog schema.")
        val array = root["items"] as? JsonArray ?: throw IOException("Missing catalog items.")
        if (array.size > CommunityPackPolicy.MAX_PAGE_SIZE) throw IOException("Too many catalog items.")
        val items = array.map { parsePack(it as? JsonObject ?: throw IOException("Invalid catalog item.")) }
        val cursor = when (val value = root["next_page_token"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.content ?: throw IOException("Invalid next-page token.")
            else -> throw IOException("Invalid next-page token.")
        }
        if (!CommunityPackPolicy.validCursor(cursor) || (items.isEmpty() && cursor != null))
            throw IOException("Invalid pagination state.")
        return CommunityPage(items, cursor)
    }

    private fun parsePack(obj: JsonObject): CommunityPack {
        val type = CommunityPackType.fromWire(obj.string("type")) ?: throw IOException("Unknown pack type.")
        val targets = obj["target_packages"] as? JsonArray ?: throw IOException("Missing target packages.")
        val ratingCount = obj.number("rating_count")
        val average = when (val value = obj["rating_average"]) {
            null, JsonNull -> if (ratingCount == 0L) 0.0 else throw IOException("Missing average rating.")
            is JsonPrimitive -> value.doubleOrNull ?: throw IOException("Invalid average rating.")
            else -> throw IOException("Invalid average rating.")
        }
        val pack = CommunityPack(
            id = obj.string("id"),
            type = type,
            title = obj.string("title"),
            description = obj.string("description"),
            targetPackages = targets.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: throw IOException("Invalid target package.") },
            author = obj.string("author"),
            license = obj.string("license"),
            mediaType = obj.string("media_type"),
            sizeBytes = obj.number("size_bytes"),
            sha256 = obj.string("sha256"),
            createdAt = obj.string("created_at"),
            downloads = obj.number("downloads"),
            ratingAverage = average,
            ratingCount = ratingCount
        )
        val violations = CommunityPackPolicy.violations(pack)
        if (violations.isNotEmpty()) throw IOException("Catalog item violates ${violations.joinToString()}.")
        return pack
    }

    private fun JsonObject.string(name: String): String =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IOException("Missing text field $name.")

    private fun JsonObject.number(name: String): Long =
        (get(name) as? JsonPrimitive)?.longOrNull ?: throw IOException("Missing numeric field $name.")

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(8192)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                if (out.size().toLong() + n > limit) throw IOException("Catalog response too large.")
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun digestFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()

    private fun validContent(file: File, mime: String): Boolean {
        val head = file.inputStream().use { input -> ByteArray(16).also { b ->
            val n = input.read(b); if (n < 0) return false
        } }
        return when (mime) {
            "image/png" -> head.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            "image/jpeg" -> head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte()
            "image/webp" -> String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
            "image/gif" -> String(head, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
            "video/mp4" -> String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
            "application/vnd.waex.theme+json" -> validPalette(decodeUtf8(file.readBytes()))
            else -> false
        }
    }

    /** Validate inert palette schema; does not map to host resource IDs or apply unsafe CSS. */
    fun validPalette(text: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj.keys != setOf("schema_version", "colors") || obj.number("schema_version") != 1L) return@runCatching false
        val colors = obj["colors"] as? JsonObject ?: return@runCatching false
        val allowed = setOf("primary", "background", "text", "secondary", "surface", "accent")
        val required = setOf("primary", "background", "text")
        colors.keys.all { it in allowed } && colors.keys.containsAll(required) &&
            colors.values.all { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?.matches(Regex("^#[0-9a-fA-F]{6}$")) == true }
    }.getOrDefault(false)

    private fun extension(mime: String): String = when (mime) {
        "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
        "image/gif" -> "gif"; "video/mp4" -> "mp4"
        "application/vnd.waex.theme+json" -> "json"
        else -> throw IllegalArgumentException("Unsupported MIME.")
    }
}
