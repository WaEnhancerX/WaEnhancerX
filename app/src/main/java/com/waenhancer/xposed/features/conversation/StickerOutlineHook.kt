package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ColorFilter
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

/**
 * Removes the white outline WhatsApp's sticker maker draws around a cut-out.
 *
 * WhatsApp builds the outline in one method that takes the source bitmap, a color filter and,
 * last, the stroke width, and that uses 1/sqrt(2) to offset the stroke diagonally. The hook
 * returns an unmodified mutable copy of the source instead of the outlined bitmap.
 */
class StickerOutlineHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "Remove Sticker Outline"

    override fun hook() {
        if (!isEnabled(PREF_KEY, false)) return
        val outline = DexSearchEngine.getInstance().findMethodWithCache(
            context, classLoader, CACHE_KEY
        ) { bridge, loader ->
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .returnType(Bitmap::class.java)
                        .paramCount(MIN_PARAMS, MAX_PARAMS)
                        .addUsingNumber(INV_SQRT_2)
                )
            ).singleOrNull { isOutlineSignature(it.paramTypeNames) }?.getMethodInstance(loader)
        }
        if (outline == null) {
            XposedBridge.log("$TAG sticker outline renderer not found")
            return
        }
        XposedBridge.hookMethod(outline, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled(PREF_KEY, false)) return
                val source = param.args.firstOrNull() as? Bitmap ?: return
                if (source.isRecycled) return
                val copy = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true) ?: return
                param.result = copy
            }
        })
    }

    companion object {
        const val PREF_KEY = "remove_sticker_white_outline"
        private const val TAG = "[WAEX][StickerOutline]"
        private const val CACHE_KEY = "waex_sticker_outline_renderer_v1"
        private const val INV_SQRT_2 = 0.70710678118
        // 2.26.44 added parameters between the color filter and the stroke width.
        private const val MIN_PARAMS = 3
        private const val MAX_PARAMS = 6

        /** Bitmap first, ColorFilter second and a float stroke width last. */
        @JvmStatic
        fun isOutlineSignature(paramTypeNames: List<String>): Boolean =
            paramTypeNames.size in MIN_PARAMS..MAX_PARAMS &&
                paramTypeNames.first() == Bitmap::class.java.name &&
                paramTypeNames[1] == ColorFilter::class.java.name &&
                paramTypeNames.last() == "float"
    }
}
