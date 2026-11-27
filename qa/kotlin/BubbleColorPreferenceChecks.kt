import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import com.waenhancer.config.PreferenceSchema
import com.waenhancer.ui.screens.settings.StyleBooleanAliasPolicy
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.features.conversation.ChatBubbleColorsHook
import de.robv.android.xposed.XposedBridge

/** Real feature and BaseFeature reader; platform, lifecycle, DexKit and hook sink are doubles. */
object BubbleColorPreferenceChecks {
    private const val PRIMARY = "bubble_color"
    private const val LEGACY = "bubble_colors"
    private const val INCOMING = 0xff102030.toInt()
    private const val OUTGOING = 0xff405060.toInt()
    private var passed = 0
    private var failed = 0

    private class Prefs : SharedPreferences {
        val values = mutableMapOf<String, Any>()
        override fun contains(key: String) = values.containsKey(key)
        override fun getBoolean(key: String, fallback: Boolean) = values[key]?.let { it as Boolean } ?: fallback
        override fun getInt(key: String, fallback: Int) = values[key]?.let { it as Int } ?: fallback
        override fun getString(key: String, fallback: String?) = values[key]?.let { it as String } ?: fallback
    }

    object Factories {
        @JvmStatic fun body(direction: Int) = Drawable()
        @JvmStatic fun padding(direction: Int) = Rect()
        @JvmStatic fun wrapper(direction: Int) = Drawable()
        @JvmStatic fun border(unused: Int, direction: Int, other: Int) = Drawable()
    }

    private fun check(condition: Boolean, reason: String) {
        if (!condition) throw AssertionError(reason)
    }

    private fun case(name: String, block: () -> Unit) {
        try { block(); passed++ }
        catch (error: Throwable) { failed++; println("FAIL|$name|${error.javaClass.name}|${error.message}") }
    }

    private fun preferences(values: Map<String, Boolean>) = Prefs().apply {
        this.values.putAll(values)
        this.values["bubble_left"] = INCOMING
        this.values["bubble_right"] = OUTGOING
    }

    private fun assertFeature(target: String, prefs: Prefs, enabled: Boolean, emptyPalette: Boolean = false) {
        XposedBridge.reset()
        DexSearchEngine.queries = 0
        DexSearchEngine.guarded = arrayOf(
            Factories::class.java.getDeclaredMethod("body", Int::class.javaPrimitiveType),
            Factories::class.java.getDeclaredMethod("padding", Int::class.javaPrimitiveType)
        )
        val feature = ChatBubbleColorsHook(Context(target), javaClass.classLoader, prefs)
        val reader = ChatBubbleColorsHook::class.java.getDeclaredMethod("isFeatureOn").apply { isAccessible = true }
        check(reader.invoke(feature) == enabled, "actual isFeatureOn differs from explicit preference contract")
        feature.hook()
        if (!enabled || emptyPalette) {
            check(DexSearchEngine.queries == 0, "disabled/default feature must not query DexKit")
            check(XposedBridge.installed.isEmpty(), "disabled/default feature must not install tint callbacks")
            return
        }
        check(DexSearchEngine.queries == 1, "one factory query")
        check(XposedBridge.installed.size == 3, "body, wrapper and border callbacks installed")
        for (registration in XposedBridge.installed) {
            for (direction in listOf(1, 3)) {
                val args: Array<Any?> = if (registration.method.parameterCount == 3)
                    arrayOf(0, direction, 0) else arrayOf(direction)
                val drawable = Drawable()
                XposedBridge.apply(registration, drawable, args)
                val tint = drawable.colorFilter as? PorterDuffColorFilter
                check(tint != null, "real callback must set a tint")
                check(tint!!.color == if (direction == 3) OUTGOING else INCOMING,
                    "incoming/outgoing color selection retained")
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val target = args.single()
        check(target == "com.whatsapp" || target == "com.whatsapp.w4b", "isolated target process required")
        val other = if (target == "com.whatsapp") "com.whatsapp.w4b" else "com.whatsapp"
        check(ChatBubbleColorsHook.PREF_ENABLED == PRIMARY, "public primary key retained")
        check(StyleBooleanAliasPolicy.canonicalKey(LEGACY) == PRIMARY, "Styles alias uses the same primary key")
        check(PreferenceSchema.entry(PRIMARY).type == PreferenceSchema.Type.BOOLEAN
                && PreferenceSchema.entry(LEGACY).type == PreferenceSchema.Type.BOOLEAN,
            "both persisted keys retain Boolean schema contracts")

        // These are explicit expected outcomes, not a reimplementation of the feature formula.
        val rows = listOf(
            Triple(null, null, false), Triple(null, false, false), Triple(null, true, true),
            Triple(false, null, false), Triple(false, false, false), Triple(false, true, false),
            Triple(true, null, true), Triple(true, false, true), Triple(true, true, true)
        )
        for ((primary, legacy, expected) in rows) {
            case("root primary=$primary legacy=$legacy") {
                val settings = mutableMapOf<String, Boolean>()
                if (primary != null) settings[PRIMARY] = primary
                if (legacy != null) settings[LEGACY] = legacy
                assertFeature(target, preferences(settings), expected)
            }
        }
        case("own scoped OFF overrides own legacy ON") {
            assertFeature(target, preferences(mapOf("$target:$PRIMARY" to false, "$target:$LEGACY" to true)), false)
        }
        case("own scoped ON survives legacy OFF") {
            assertFeature(target, preferences(mapOf("$target:$PRIMARY" to true, "$target:$LEGACY" to false)), true)
        }
        case("own scoped legacy-only fallback retained") {
            assertFeature(target, preferences(mapOf("$target:$LEGACY" to true)), true)
        }
        case("other target cannot activate this process") {
            assertFeature(target, preferences(mapOf("$other:$PRIMARY" to true, "$other:$LEGACY" to true)), false)
        }
        case("bridge-resolved primary OFF beats stale scoped values") {
            assertFeature(target, preferences(mapOf(PRIMARY to false, LEGACY to true, "$target:$PRIMARY" to true)), false)
        }
        case("legacy-only ON can be turned OFF before host restart") {
            val prefs = preferences(mapOf(LEGACY to true))
            assertFeature(target, prefs, true)
            // Model the successful canonical Boolean write already wired in both settings UIs.
            prefs.values[PRIMARY] = false
            assertFeature(target, prefs, false)
            check(prefs.values[LEGACY] == true, "no alias migration or cleanup required")
        }
        case("enabled with default palette still leaves host colors alone") {
            val prefs = preferences(mapOf(PRIMARY to true))
            prefs.values.remove("bubble_left"); prefs.values.remove("bubble_right")
            assertFeature(target, prefs, true, emptyPalette = true)
        }
        println("RESULT|bubble_color_precedence|$target|$passed|$failed")
        if (failed > 0) throw AssertionError("Bubble color preference regression")
    }
}
