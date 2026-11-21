package com.waenhancer.xposed.features.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerOutlineHookTest {

    private val bitmap = "android.graphics.Bitmap"
    private val filter = "android.graphics.ColorFilter"

    @Test
    fun acceptsLegacyAndNewerOutlineSignatures() {
        assertTrue(StickerOutlineHook.isOutlineSignature(listOf(bitmap, filter, "float")))
        assertTrue(StickerOutlineHook.isOutlineSignature(listOf(bitmap, filter, "int", "boolean", "float")))
        // 2.26.44 shape: six parameters, stroke width still last.
        assertTrue(StickerOutlineHook.isOutlineSignature(listOf(bitmap, filter, "int", "int", "boolean", "float")))
    }

    @Test
    fun rejectsUnrelatedBitmapMethods() {
        assertFalse(StickerOutlineHook.isOutlineSignature(listOf(bitmap, "float")))
        assertFalse(StickerOutlineHook.isOutlineSignature(listOf(filter, bitmap, "float")))
        assertFalse(StickerOutlineHook.isOutlineSignature(listOf(bitmap, filter, "int")))
        assertFalse(
            StickerOutlineHook.isOutlineSignature(listOf(bitmap, filter, "int", "int", "int", "int", "float"))
        )
    }
}
