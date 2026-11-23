package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Tints conversation message bubbles with the incoming/outgoing colors picked in the
 * Bubble Colors screen.
 *
 * WhatsApp builds every bubble layer (body, border and the date/footer wrapper) through
 * direction-aware factories that share one "direction=" guard string. Each factory takes the
 * bubble direction as an int, where [DIRECTION_OUTGOING] marks the user's own messages.
 */
class ChatBubbleColorsHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "Chat Bubble Colors"

    override fun hook() {
        if (!isFeatureOn()) return

        val palette = BubblePalette(
            incoming = readColor(PREF_INCOMING),
            outgoing = readColor(PREF_OUTGOING)
        )
        if (palette.isEmpty) return

        val factories = resolveFactories()
        if (factories.isEmpty()) {
            XposedBridge.log("$TAG bubble drawable factories not found")
            return
        }

        val tint = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val drawable = param.result as? Drawable ?: return
                val direction = directionOf(param.method as Method, param.args) ?: return
                val color = palette.forDirection(direction)
                if (color == NO_COLOR) return
                drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
        }
        factories.forEach { XposedBridge.hookMethod(it, tint) }
    }

    private fun isFeatureOn(): Boolean =
        isEnabled(PREF_ENABLED, false) || isEnabled(LEGACY_PREF_ENABLED, false)

    private fun readColor(key: String): Int = try {
        getIntPref(key, NO_COLOR)
    } catch (_: ClassCastException) {
        NO_COLOR
    }

    /**
     * Finds the bubble body factory plus the sibling factories (date wrapper and border) that
     * live next to the bubble's padding resolver.
     */
    private fun resolveFactories(): List<Method> {
        val found = DexSearchEngine.getInstance().findMethodsWithCache(
            context, classLoader, CACHE_KEY
        ) { bridge, loader ->
            val guarded = bridge.findMethod(
                FindMethod.create().matcher(MethodMatcher.create().addUsingString(DIRECTION_GUARD))
            ).mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }

            val body = guarded.firstOrNull { it.returnType == Drawable::class.java }
            val paddingOwner = guarded.firstOrNull { it.returnType == Rect::class.java }?.declaringClass

            val siblings = paddingOwner?.declaredMethods
                ?.filter { it != body && isLayerFactory(it) }
                .orEmpty()
            val wrapper = siblings.firstOrNull { it.parameterCount in 1..2 && firstParamIsInt(it) }
            val border = siblings.firstOrNull { it.parameterCount == 3 }

            listOfNotNull(body, wrapper, border).distinct().toTypedArray()
                .takeIf { it.isNotEmpty() }
        }
        return found?.toList().orEmpty()
    }

    private fun isLayerFactory(method: Method): Boolean =
        method.returnType == Drawable::class.java && !Modifier.isAbstract(method.modifiers)

    private fun firstParamIsInt(method: Method): Boolean =
        method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType

    /** The border factory carries the direction second; the others carry it first. */
    private fun directionOf(method: Method, args: Array<Any?>): Int? {
        val index = if (method.parameterCount == 3) 1 else 0
        return args.getOrNull(index) as? Int
    }

    private class BubblePalette(val incoming: Int, val outgoing: Int) {
        val isEmpty: Boolean get() = incoming == NO_COLOR && outgoing == NO_COLOR

        fun forDirection(direction: Int): Int =
            if (direction == DIRECTION_OUTGOING) outgoing else incoming
    }

    companion object {
        const val PREF_ENABLED = "bubble_color"
        const val PREF_INCOMING = "bubble_left"
        const val PREF_OUTGOING = "bubble_right"

        /** Older builds toggled this UI-only key; honour it so existing users keep the feature. */
        private const val LEGACY_PREF_ENABLED = "bubble_colors"

        /** 0 means "keep WhatsApp's own color". */
        const val NO_COLOR = 0

        private const val DIRECTION_OUTGOING = 3
        private const val DIRECTION_GUARD = "Unreachable code: direction="
        private const val CACHE_KEY = "waex_bubble_layer_factories_v2"
        private const val TAG = "[WAEX][BubbleColors]"
    }
}
