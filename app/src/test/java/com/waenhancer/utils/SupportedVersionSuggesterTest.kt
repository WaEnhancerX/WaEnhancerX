package com.waenhancer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedVersionSuggesterTest {

    private val rules = listOf("2.26.45.xx", "2.26.44.xx", "2.26.43.xx", "2.26.42.xx", "2.26.41.xx")

    @Test
    fun `compares versions numerically not lexically`() {
        assertTrue(SupportedVersionSuggester.compareVersions("2.26.45.9", "2.26.45.10") < 0)
        assertTrue(SupportedVersionSuggester.compareVersions("2.26.45.71", "2.26.45.70") > 0)
        assertEquals(0, SupportedVersionSuggester.compareVersions("2.26.45", "2.26.45.0"))
    }

    @Test
    fun `picks the newest candidate that matches a built-in rule`() {
        val candidates = listOf("2.26.45.70", "2.26.45.71", "2.26.44.70", "2.23.8.78", "2.17.253")
        assertEquals("2.26.45.71", SupportedVersionSuggester.pickLatestSupported(candidates, rules))
    }

    @Test
    fun `ignores candidates outside the supported rules`() {
        val candidates = listOf("2.27.1.5", "2.23.8.78", "2.17.253")
        assertNull(SupportedVersionSuggester.pickLatestSupported(candidates, rules))
    }

    @Test
    fun `ignores malformed candidate names`() {
        val candidates = listOf("", "beta", "2.26.45.xx", "2.26.44.70 beta", "2.26.43.76")
        assertEquals("2.26.43.76", SupportedVersionSuggester.pickLatestSupported(candidates, rules))
    }

    @Test
    fun `accepts a v prefix on candidates`() {
        assertEquals("2.26.45.71", SupportedVersionSuggester.pickLatestSupported(listOf("v2.26.45.71"), rules))
    }

    @Test
    fun `newest built-in rule is independent of list order`() {
        assertEquals("2.26.45.xx", SupportedVersionSuggester.newestBuiltinRule(listOf("2.26.41.xx", "2.26.45.xx", "2.26.43.xx")))
        assertNull(SupportedVersionSuggester.newestBuiltinRule(emptyList()))
    }

    @Test
    fun `suggest prefers an exact build and falls back to the newest rule`() {
        assertEquals(VersionSuggestion("2.26.45.71", true), SupportedVersionSuggester.suggest(listOf("2.26.45.71"), rules))
        assertEquals(VersionSuggestion("2.26.45.xx", false), SupportedVersionSuggester.suggest(emptyList(), rules))
        assertNull(SupportedVersionSuggester.suggest(listOf("2.26.45.71"), emptyList()))
    }

    @Test
    fun `only official packages are suggestion targets`() {
        assertTrue(SupportedVersionSuggester.isSuggestionTarget("com.whatsapp"))
        assertTrue(SupportedVersionSuggester.isSuggestionTarget("com.whatsapp.w4b"))
        assertFalse(SupportedVersionSuggester.isSuggestionTarget("com.ob5whatsapp"))
    }

    @Test
    fun `each official app links to its own release page`() {
        assertEquals(
            "https://www.apkmirror.com/apk/whatsapp-inc/whatsapp-messenger/",
            SupportedVersionSuggester.downloadPageUrl("com.whatsapp")
        )
        assertEquals(
            "https://www.apkmirror.com/apk/whatsapp-inc/whatsapp-messenger-business/",
            SupportedVersionSuggester.downloadPageUrl("com.whatsapp.w4b")
        )
        assertNull(SupportedVersionSuggester.downloadPageUrl("com.ob5whatsapp"))
    }
}
