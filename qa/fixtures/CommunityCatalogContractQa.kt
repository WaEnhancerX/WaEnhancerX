package com.waenhancer.community

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Run directly with a pinned Kotlin/JDK classpath; never contacts a real service. */
object CommunityCatalogContractQa {
    private var checks = 0
    private const val ID = "550e8400-e29b-41d4-a716-446655440000"

    private fun expect(condition: Boolean, label: String) {
        checks++
        if (!condition) error("FAIL: $label")
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun pack(bytes: ByteArray, media: String = "image/png", type: CommunityPackType = CommunityPackType.WALLPAPER_STATIC) =
        CommunityPack(ID, type, "Sunset", "One test pack", listOf("com.whatsapp", "com.whatsapp.w4b"),
            "Test author", "CC0-1.0", media, bytes.size.toLong(), hash(bytes), "2026-11-18T12:00:00Z", 3, 0.0, 0)

    private fun fixtureJson(pack: CommunityPack): String = """
        {"schema_version":1,"items":[{"id":"${pack.id}","type":"${pack.type.wireValue}",
        "title":"${pack.title}","description":"${pack.description}","target_packages":["com.whatsapp","com.whatsapp.w4b"],
        "author":"${pack.author}","license":"${pack.license}","media_type":"${pack.mediaType}",
        "size_bytes":${pack.sizeBytes},"sha256":"${pack.sha256}","created_at":"${pack.createdAt}",
        "downloads":3,"rating_average":null,"rating_count":0,"download_url":"/v1/packs/${pack.id}/file"}],
        "next_page_token":"next/opaque+1","total":1}
    """.trimIndent()

    private fun fake(
        status: Int, contentType: String, payload: ByteArray, capture: MutableList<String>,
        responseHeaders: Map<String, String> = emptyMap()
    ): CommunityCatalogClient {
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain ->
            capture += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").header("Content-Type", contentType)
                .apply { responseHeaders.forEach { (name, value) -> header(name, value) } }
                .body(payload.toResponseBody(contentType.toMediaType())).build()
        }).build()
        return CommunityCatalogClient(http)
    }

    private fun onePixelPng(): ByteArray {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val bytes = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", bytes))
        return bytes.toByteArray()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val image = onePixelPng()
        val original = pack(image)
        expect(CommunityPackPolicy.violations(original).isEmpty(), "baseline valid")
        expect(CommunityPackPolicy.violations(original.copy(sha256 = "0".repeat(63))).contains("sha256"), "truncated checksum")
        expect(CommunityPackPolicy.violations(original.copy(id = "../../etc")).contains("id"), "traversal ID")
        expect(CommunityPackPolicy.violations(original.copy(mediaType = "application/x-sh")).contains("media_type"), "executable MIME")
        expect(CommunityPackPolicy.violations(original.copy(sizeBytes = 13L * 1024 * 1024)).isEmpty(),
            "server-approved 13 MiB static pack stays valid")
        expect(CommunityPackPolicy.violations(original.copy(sizeBytes = 16L * 1024 * 1024)).isEmpty(),
            "static 16 MiB boundary included")
        expect(CommunityPackPolicy.violations(original.copy(sizeBytes = CommunityPackPolicy.MAX_STATIC_BYTES + 1)).contains("size_bytes"), "size bound")
        val animated = original.copy(type = CommunityPackType.WALLPAPER_ANIMATED, mediaType = "video/mp4")
        expect(CommunityPackPolicy.violations(animated.copy(sizeBytes = 16L * 1024 * 1024)).isEmpty(),
            "animated 16 MiB boundary included")
        expect(CommunityPackPolicy.violations(animated.copy(sizeBytes = 16L * 1024 * 1024 + 1)).contains("size_bytes"),
            "animated server 16 MiB cap")
        expect(CommunityPackPolicy.violations(original.copy(ratingAverage = 4.0)).contains("ratings"), "unrated average")
        expect(CommunityPackPolicy.violations(original.copy(ratingAverage = Double.NaN)).contains("ratings"), "NaN rating")
        expect(CommunityPackPolicy.violations(original.copy(targetPackages = listOf("com.whatsapp", "com.whatsapp"))).contains("target_packages"), "duplicate target")
        expect(CommunityPackPolicy.violations(original.copy(targetPackages = listOf("../escape"))).contains("target_packages"), "bad target")
        expect(CommunityPackPolicy.violations(original.copy(targetPackages = listOf("com.gbwhatsapp"))).contains("target_packages"),
            "no unapproved mod target")
        expect(CommunityPackPolicy.violations(original.copy(targetPackages = listOf("com.whatsapp.w4b"))).isEmpty(),
            "Business-only pack accepted")
        expect(CommunityPackPolicy.violations(original.copy(license = "Proprietary")).contains("license"),
            "reject unapproved license")
        for (license in listOf("CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0"))
            expect(CommunityPackPolicy.violations(original.copy(license = license)).isEmpty(), "allowed license $license")
        expect(CommunityPackPolicy.violations(original.copy(title = "T".repeat(80))).isEmpty(), "title 80 boundary")
        expect(CommunityPackPolicy.violations(original.copy(title = "T".repeat(81))).contains("title"), "title 81 rejected")
        val emoji = "\uD83D\uDE00"
        expect(CommunityPackPolicy.violations(original.copy(title = emoji.repeat(80))).isEmpty(),
            "Python-compatible Unicode codepoint length: 80 emoji")
        expect(CommunityPackPolicy.violations(original.copy(title = emoji.repeat(81))).contains("title"),
            "Python-compatible Unicode codepoint length: 81 emoji rejected")
        expect(CommunityPackPolicy.violations(original.copy(description = "x".repeat(500))).isEmpty(), "description 500 boundary")
        expect(CommunityPackPolicy.violations(original.copy(description = "x".repeat(501))).contains("description"), "description 501 rejected")
        expect(CommunityPackPolicy.violations(original.copy(author = "A".repeat(60))).isEmpty(), "author 60 boundary")
        expect(CommunityPackPolicy.violations(original.copy(author = "A".repeat(61))).contains("author"), "author 61 rejected")
        expect(CommunityPackPolicy.violations(original.copy(title = "<script>")).contains("title"), "reject markup title")
        expect(CommunityPackPolicy.violations(original.copy(description = "A <bad> tag")).contains("description"), "reject markup description")
        expect(CommunityPackPolicy.trustedApiRoot("https://catalog.example.org") != null, "HTTPS endpoint")
        for (url in listOf("http://catalog.example.org", "https://u:p@catalog.example.org", "https://catalog.example.org/x", "https://catalog.example.org/?token=1", "file:///tmp/x"))
            expect(CommunityPackPolicy.trustedApiRoot(url) == null, "reject endpoint: $url")
        expect(!CommunityPackPolicy.validCursor("\nmalicious"), "control in cursor")

        val api = "https://catalog.example.org/"
        val calls = mutableListOf<String>()
        val catalog = fake(200, "application/json", fixtureJson(original).toByteArray(), calls)
        val page = catalog.list(api, CommunitySort.POPULAR, CommunityPackType.WALLPAPER_STATIC, "after/first+2").getOrThrow()
        expect(page.items == listOf(original), "parse real backend v1 DTO including null average")
        expect(page.nextPageToken == "next/opaque+1", "opaque cursor")
        expect(calls.single().contains("sort=popular") && calls.single().contains("type=wallpaper_static") &&
            calls.single().contains("pageToken=after%2Ffirst%2B2"), "query-safe sort, type, cursor")
        expect(catalog.list("http://catalog.example.org").isFailure, "reject cleartext request")
        expect(catalog.list(api, pageToken = "\n").isFailure, "reject invalid cursor before network")
        expect(fake(302, "application/json", ByteArray(0), mutableListOf()).list(api).isFailure, "no redirects")
        val forgedRedirectClient = OkHttpClient.Builder().followRedirects(true).addInterceptor(Interceptor { chain ->
            Response.Builder().request(Request.Builder().url("https://attacker.invalid/v1/packs").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("Synthetic redirected URL")
                .header("Content-Type", "application/json")
                .body(fixtureJson(original).toResponseBody("application/json".toMediaType())).build()
        }).build()
        expect(CommunityCatalogClient(forgedRedirectClient).list(api).isFailure,
            "injected client cannot accept response associated with foreign URL")
        expect(fake(200, "text/html", fixtureJson(original).toByteArray(), mutableListOf()).list(api).isFailure, "reject catalog MIME")
        expect(fake(200, "application/json", ByteArray(CommunityPackPolicy.MAX_RESPONSE_BYTES + 1), mutableListOf()).list(api).isFailure, "bounded JSON")

        val dir = java.nio.file.Files.createTempDirectory("waex-catalog-qa-").toFile()
        try {
            val dlCalls = mutableListOf<String>()
            val downloader = fake(200, "image/png", image, dlCalls)
            val stored = downloader.download(api, original, dir).getOrThrow()
            expect(stored.isFile && stored.readBytes().contentEquals(image), "verified private file stored")
            expect(dlCalls.single().endsWith("/v1/packs/$ID/file"), "download URL same origin")
            expect(downloader.download(api, original, dir).getOrThrow() == stored, "idempotent verified download")
            expect(fake(200, "image/png", image, mutableListOf()).download(api, original.copy(sha256 = "a".repeat(64)), dir).isFailure,
                "reject wrong checksum")
            expect(fake(200, "image/jpeg", image, mutableListOf()).download(api, original, File(dir, "negative-mime")).isFailure,
                "reject MIME mismatch")
            expect(fake(200, "image/png", image, mutableListOf(),
                mapOf("X-WAEX-SHA256" to "0".repeat(64))).download(api, original, File(dir, "negative-header")).isFailure,
                "reject server digest header disagreement")
            expect(fake(206, "image/png", image, mutableListOf()).download(api, original, File(dir, "negative-partial")).isFailure,
                "reject partial HTTP response")
            expect(fake(200, "image/png", image, mutableListOf()).download(api, original.copy(sizeBytes = image.size.toLong() - 1), dir).isFailure,
                "reject length mismatch")
            val nonsense = "not an image at all".toByteArray()
            expect(fake(200, "image/png", nonsense, mutableListOf()).download(api, pack(nonsense), dir).isFailure,
                "reject magic mismatch even with matching hash")
            expect(dir.walkTopDown().none { it.name.endsWith(".partial") }, "failed downloads clean temp")
        } finally {
            dir.deleteRecursively()
        }

        val palette = """{"schema_version":1,"colors":{"primary":"#AABBCC","background":"#000000","text":"#FFFFFF"}}"""
        expect(catalog.validPalette(palette), "valid inert palette")
        expect(!catalog.validPalette(palette.replace("#AABBCC", "url(https://remote)")), "reject remote styling")
        expect(!catalog.validPalette(palette.replace("}}", ",\"javascript\":\"alert(1)\"}}")), "reject unexpected key")
        expect(!catalog.validPalette(palette.replace("\"schema_version\":1", "\"schema_version\":2")), "reject unknown schema")
        println("PASS: $checks independent catalog contract checks; no server or Android data contacted.")
    }
}
