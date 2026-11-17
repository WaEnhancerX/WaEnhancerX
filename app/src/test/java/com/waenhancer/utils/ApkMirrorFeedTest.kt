package com.waenhancer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkMirrorFeedTest {

    private val rules = listOf("2.26.45.xx", "2.26.44.xx", "2.26.43.xx")

    private fun item(title: String, link: String) =
        "<item><title>$title</title><link>$link</link><pubDate>x</pubDate></item>"

    private fun feed(vararg items: String) =
        "<rss version=\"2.0\"><channel><title>WhatsApp</title>${items.joinToString("")}</channel></rss>"

    private val base = "https://www.apkmirror.com/apk/whatsapp-inc/whatsapp-messenger"

    @Test
    fun `feed urls belong to the right app and clones get none`() {
        assertEquals(
            "https://www.apkmirror.com/apk/whatsapp-inc/whatsapp-messenger/feed/",
            ApkMirrorFeed.feedUrl("com.whatsapp")
        )
        assertEquals(
            "https://www.apkmirror.com/apk/whatsapp-inc/whatsapp-messenger-business/feed/",
            ApkMirrorFeed.feedUrl("com.whatsapp.w4b")
        )
        assertNull(ApkMirrorFeed.feedUrl("com.ob5whatsapp"))
    }

    @Test
    fun `parses versions and links and drops betas`() {
        val xml = feed(
            item("WhatsApp Messenger 2.26.45.71", "$base/whatsapp-messenger-2-26-45-71-release/"),
            item("WhatsApp Messenger 2.26.46.4 beta", "$base/whatsapp-messenger-2-26-46-4-release/")
        )
        assertEquals(
            listOf(ApkMirrorRelease("2.26.45.71", "$base/whatsapp-messenger-2-26-45-71-release/")),
            ApkMirrorFeed.parse(xml, "com.whatsapp")
        )
    }

    @Test
    fun `reads the publish date`() {
        val xml = feed(
            "<item><title>WhatsApp Messenger 2.26.45.71</title><link>$base/a/</link>" +
                "<pubDate>Mon, 16 Nov 2026 14:03:12 +0000</pubDate></item>"
        )
        assertEquals(1794837792000L, ApkMirrorFeed.parse(xml, "com.whatsapp").single().publishedAt)
        assertNull(ApkMirrorFeed.parseDate("not a date"))
    }

    @Test
    fun `each app only accepts its own entries`() {
        val xml = feed(
            item("WhatsApp Messenger 2.26.45.71", "$base/a/"),
            item("WhatsApp Business 2.26.45.78", "$base/b/")
        )
        assertEquals(listOf("2.26.45.71"), ApkMirrorFeed.parse(xml, "com.whatsapp").map { it.version })
        assertEquals(listOf("2.26.45.78"), ApkMirrorFeed.parse(xml, "com.whatsapp.w4b").map { it.version })
    }

    @Test
    fun `decodes entities and cdata`() {
        val xml = feed(
            "<item><title><![CDATA[WhatsApp Messenger 2.26.44.80]]></title>" +
                "<link>https://www.apkmirror.com/apk/x/?a=1&amp;b=2</link></item>"
        )
        assertEquals(
            "https://www.apkmirror.com/apk/x/?a=1&b=2",
            ApkMirrorFeed.parse(xml, "com.whatsapp").single().link
        )
    }

    @Test
    fun `rejects untrusted links and titles without a version`() {
        val xml = feed(
            item("WhatsApp Messenger 2.26.45.71", "http://evil.example/apk"),
            item("WhatsApp Messenger 2.26.45.72", "https://www.apkmirror.com.evil.example/apk"),
            item("WhatsApp Messenger", "$base/none/")
        )
        assertTrue(ApkMirrorFeed.parse(xml, "com.whatsapp").isEmpty())
        assertTrue(ApkMirrorFeed.isTrustedLink("https://www.apkmirror.com/apk/x/"))
        assertFalse(ApkMirrorFeed.isTrustedLink("javascript:alert(1)"))
    }

    @Test
    fun `merges pages without duplicate versions`() {
        val page1 = feed(item("WhatsApp Messenger 2.26.45.71", "$base/1/"))
        val page2 = feed(
            item("WhatsApp Messenger 2.26.45.71", "$base/dup/"),
            item("WhatsApp Messenger 2.26.44.80", "$base/2/")
        )
        val merged = ApkMirrorFeed.parseAll(listOf(page1, page2), "com.whatsapp")
        assertEquals(listOf("2.26.45.71", "2.26.44.80"), merged.map { it.version })
        assertEquals("$base/1/", merged.first().link)
    }

    @Test
    fun `keeps every supported pattern, newest first`() {
        val releases = listOf("2.26.43.76", "2.26.45.9", "2.26.45.71", "2.26.44.80", "2.26.46.2", "2.25.1.1")
            .map { ApkMirrorRelease(it, "$base/$it/") }
        assertEquals(
            listOf("2.26.45.71", "2.26.45.9", "2.26.44.80", "2.26.43.76"),
            ApkMirrorFeed.filterSupported(releases, rules).map { it.version }
        )
    }
}
