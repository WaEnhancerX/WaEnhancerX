package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Chat Bubble Custom Colors Hook.
 * Dynamically tints left and right message chat bubbles with user-configured color filters.
 */
class ChatBubbleColorsHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "Chat Bubble Colors"

    override fun hook() {
        if (!isEnabled("bubble_colors", false)) return

        val incomingHex = prefs.getString("bubble_incoming_color", "") ?: ""
        val outgoingHex = prefs.getString("bubble_outgoing_color", "") ?: ""

        val leftColor = parseColorSafe(incomingHex)
        val rightColor = parseColorSafe(outgoingHex)

        if (leftColor == null && rightColor == null) return

        hookBubbleDrawables(leftColor, rightColor)
    }

    private fun hookBubbleDrawables(leftColor: Int?, rightColor: Int?) {
        try {
            val bubbleMethods = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_bubble_drawable_method"
            ) { bridge, loader ->
                val results = bridge.findMethod(
                    FindMethod.create().matcher {
                        addUsingString("balloon_incoming_normal", StringMatchType.Contains)
                        returnType(Drawable::class.java)
                    }
                )
                if (results.isNotEmpty()) results[0].getMethodInstance(loader) else null
            }

            if (bubbleMethods != null) {
                XposedBridge.hookMethod(bubbleMethods, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val drawable = param.result as? Drawable ?: return
                        val isRight = (param.args.getOrNull(0) as? Int) == 3

                        val targetColor = if (isRight) rightColor else leftColor
                        if (targetColor != null) {
                            drawable.colorFilter = PorterDuffColorFilter(targetColor, PorterDuff.Mode.SRC_IN)
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][BubbleColors] Error applying bubble colors: ${t.message}")
        }
    }

    private fun parseColorSafe(hex: String): Int? {
        if (hex.isBlank()) return null
        return try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (_: Throwable) {
            null
        }
    }
}
