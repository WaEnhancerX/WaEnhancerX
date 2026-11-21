package com.waenhancer.core.emojis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEmojiManagerTest {

    @Test
    fun testCustomEmojiPackDisplayName() {
        val pack = CustomEmojiPack("Apple (iOS).ttf", 27287540L, "https://emoji-fonts.waex.mubashar.dev/Apple%20(iOS).ttf")
        assertEquals("Apple (iOS)", pack.displayName)
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("26.0 MB", CustomEmojiManager.formatFileSize(27287540L))
        assertEquals("2.5 MB", CustomEmojiManager.formatFileSize(2653968L))
        assertEquals("", CustomEmojiManager.formatFileSize(0L))
        assertEquals("", CustomEmojiManager.formatFileSize(-100L))
    }

    @Test
    fun testCustomEmojiPackFormattedSize() {
        val pack = CustomEmojiPack("Twitter (Twemoji).ttf", 2653968L, "https://emoji-fonts.waex.mubashar.dev/Twitter%20(Twemoji).ttf")
        assertEquals("2.5 MB", pack.formattedSize)
    }

    @Test
    fun testFormatZeroAndNegativeBytes() {
        assertEquals("", CustomEmojiManager.formatFileSize(0))
        assertEquals("", CustomEmojiManager.formatFileSize(-1))
    }
}

