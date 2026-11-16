package com.waenhancer.xposed.features.customization

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.waenhancer.theme.ThemeIds

/** Applies CSS-like rules to WhatsApp views without requiring a theme package. */
class CssThemeHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String = "Custom Theme CSS"

    @Volatile
    private var rules: List<StyleRule> = emptyList()
    private val forcedVisibility = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val assetCache = LruCache<String, Bitmap>(24)
    private val missingAssets = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var themeId: String? = null

    override fun hook() {
        reloadRules()

        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyRules(param.thisObject as? View ?: return)
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onDetachedFromWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    forcedVisibility.remove(param.thisObject as? View)
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setVisibility",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    forcedVisibility[param.thisObject as? View]?.let { param.args[0] = it }
                }
            }
        )

        ActivityTracker.addListener { activity ->
            mainHandler.post { applyRecursively(activity.window?.decorView) }
        }
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == CSS_KEY || key == LEGACY_CSS_KEY || key == THEME_KEY) {
                reloadRules()
                mainHandler.post {
                    applyRecursively(ActivityTracker.getCurrentActivity()?.window?.decorView)
                }
            }
        }
    }

    private fun reloadRules() {
        forcedVisibility.clear()
        assetCache.evictAll()
        missingAssets.clear()
        themeId = prefs.getString(THEME_KEY, null)?.takeIf(ThemeIds::isValidId)
        // An explicit (even empty) css_theme wins; the legacy key only serves installs that never set it.
        val css = if (prefs.contains(CSS_KEY)) prefs.getString("css_theme", "").orEmpty()
        else prefs.getString("custom_css", "").orEmpty()
        rules = runCatching { parseRules(css) }
            .onFailure { XposedBridge.log("WAEX: invalid custom CSS: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun applyRecursively(view: View?) {
        if (view == null) return
        applyRules(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyRecursively(view.getChildAt(index))
        }
    }

    private fun applyRules(view: View) {
        val currentRules = rules
        if (currentRules.isEmpty()) return
        val activity = ActivityTracker.getCurrentActivity()
        currentRules.forEach { rule ->
            if (rule.selector.matches(view, activity)) applyDeclarations(view, rule.declarations)
        }
    }

    private fun applyDeclarations(view: View, declarations: Map<String, String>) {
        var requestLayout = false
        declarations.forEach { (property, rawValue) ->
            val value = rawValue.removeSuffix("!important").trim()
            runCatching {
                when (property) {
                    "display" -> {
                        val visibility = when (value.lowercase()) {
                            "none" -> View.GONE
                            "invisible", "hidden" -> View.INVISIBLE
                            "block", "visible" -> View.VISIBLE
                            else -> null
                        }
                        if (visibility != null) {
                            forcedVisibility[view] = visibility
                            view.visibility = visibility
                        }
                    }
                    "alpha", "opacity" -> value.toFloatOrNull()?.let {
                        view.alpha = it.coerceIn(0f, 1f)
                    }
                    "background", "background-color", "background-image" -> applyBackground(view, value, declarations)
                    "foreground" -> (parseUrlDrawable(view, value, declarations) ?: parseColor(view, value)?.let { ColorDrawable(it) })
                        ?.let { view.foreground = it }
                    "width" -> view.layoutParams?.let { params ->
                        resolveDimension(view, value, true)?.let { params.width = it; requestLayout = true }
                    }
                    "height" -> view.layoutParams?.let { params ->
                        resolveDimension(view, value, false)?.let { params.height = it; requestLayout = true }
                    }
                    "margin" -> if (applyBoxValues(view, value, margin = true)) requestLayout = true
                    "padding" -> applyBoxValues(view, value, margin = false)
                    "margin-left", "margin-top", "margin-right", "margin-bottom" -> {
                        if (applySingleMargin(view, property, value)) requestLayout = true
                    }
                    "padding-left", "padding-top", "padding-right", "padding-bottom" ->
                        applySinglePadding(view, property, value)
                    "border-radius" -> applyRoundedBackground(view, value)
                    "border" -> applyBorder(view, value)
                    "box-shadow", "elevation" -> parseDimension(view, value.substringBefore(' '))?.let { view.elevation = it.toFloat() }
                    "rotation" -> value.removeSuffix("deg").toFloatOrNull()?.let { view.rotation = it }
                    "transform" -> applyTransform(view, value)
                    "color-tint" -> applyTint(view, value)
                    "color-filter" -> applyColorFilter(view, value)
                    "color" -> if (view is TextView) parseColor(view, value)?.let(view::setTextColor)
                    "font-size" -> if (view is TextView) parseDimension(view, value)?.let {
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.toFloat())
                    }
                    "font-weight" -> if (view is TextView) {
                        val style = if (value.lowercase() in setOf("bold", "600", "700", "800", "900")) Typeface.BOLD else Typeface.NORMAL
                        view.setTypeface(view.typeface, style)
                    }
                    "font-style" -> if (view is TextView) {
                        view.setTypeface(view.typeface, if (value.equals("italic", true)) Typeface.ITALIC else Typeface.NORMAL)
                    }
                    "text-align" -> if (view is TextView) view.gravity = when (value.lowercase()) {
                        "center" -> Gravity.CENTER
                        "right", "end" -> Gravity.END or Gravity.CENTER_VERTICAL
                        else -> Gravity.START or Gravity.CENTER_VERTICAL
                    }
                    "text-transform" -> if (view is TextView) when (value.lowercase()) {
                        "uppercase" -> view.isAllCaps = true
                        "lowercase" -> { view.isAllCaps = false; view.text = view.text.toString().lowercase() }
                        "none" -> view.isAllCaps = false
                    }
                    "text-decoration" -> if (view is TextView) applyTextDecoration(view, value)
                }
            }
        }
        if (requestLayout) view.requestLayout()
    }

    private fun applyBackground(view: View, value: String, declarations: Map<String, String>) {
        if (value.equals("none", true) || value.equals("transparent", true)) {
            view.background = null
            return
        }
        parseLinearGradient(view, value)?.let { view.background = it; return }
        parseUrlDrawable(view, value, declarations)?.let { view.background = it; return }
        parseColor(view, value)?.let(view::setBackgroundColor)
    }

    /** Resolves `url('file.png')` against the active theme's bundled assets, honouring `background-size`. */
    private fun parseUrlDrawable(view: View, value: String, declarations: Map<String, String>?): Drawable? {
        val name = URL.find(value)?.groupValues?.get(1)?.trim('"', '\'', ' ') ?: return null
        val bitmap = loadAsset(name) ?: return null
        val size = declarations?.get("background-size")?.trim()?.lowercase()
        return AssetDrawable(BitmapDrawable(view.resources, bitmap), cover = size == "cover", contain = size == "contain")
    }

    private fun loadAsset(name: String): Bitmap? {
        val id = themeId ?: return null
        if (!ThemeIds.isValidAssetName(name)) return null
        val key = "$id/$name"
        assetCache.get(key)?.let { return it }
        if (key in missingAssets) return null
        val bitmap = runCatching {
            val result = context.contentResolver.call(
                Uri.parse("content://com.waenhancer.hookprovider"), "theme_asset", id,
                android.os.Bundle().apply { putString("name", name) }
            )
            val descriptor = result?.getParcelable<ParcelFileDescriptor>("descriptor") ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > MAX_ASSET_EDGE || bounds.outHeight / sample > MAX_ASSET_EDGE) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.onFailure { XposedBridge.log("WAEX: theme asset $name failed: ${it.message}") }.getOrNull()
        if (bitmap != null) assetCache.put(key, bitmap) else missingAssets.add(key)
        return bitmap
    }

    /** Draws a bitmap stretched (default), center-cropped (`cover`) or letterboxed (`contain`). */
    private class AssetDrawable(private val bitmap: BitmapDrawable, private val cover: Boolean, private val contain: Boolean) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val source = bitmap.bitmap
            if (!cover && !contain) {
                bitmap.setBounds(b.left, b.top, b.right, b.bottom)
                bitmap.draw(canvas)
                return
            }
            val scale = if (cover) maxOf(b.width() / source.width.toFloat(), b.height() / source.height.toFloat())
            else minOf(b.width() / source.width.toFloat(), b.height() / source.height.toFloat())
            val w = (source.width * scale).roundToInt()
            val h = (source.height * scale).roundToInt()
            val left = b.left + (b.width() - w) / 2
            val top = b.top + (b.height() - h) / 2
            val save = canvas.save()
            canvas.clipRect(b)
            bitmap.setBounds(left, top, left + w, top + h)
            bitmap.draw(canvas)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { bitmap.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { bitmap.colorFilter = colorFilter }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun applyRoundedBackground(view: View, value: String) {
        val radius = parseDimension(view, value)?.toFloat() ?: return
        val currentColor = (view.background as? ColorDrawable)?.color ?: Color.TRANSPARENT
        view.background = GradientDrawable().apply { setColor(currentColor); cornerRadius = radius }
    }

    private fun applyBorder(view: View, value: String) {
        val tokens = value.split(WHITESPACE).filter(String::isNotBlank)
        val width = tokens.firstNotNullOfOrNull { parseDimension(view, it) } ?: return
        val color = tokens.firstNotNullOfOrNull { parseColor(view, it) } ?: return
        val existing = view.background as? GradientDrawable
        val drawable = existing ?: GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        drawable.setStroke(width, color)
        view.background = drawable
    }

    private fun applyTransform(view: View, value: String) {
        ROTATE.find(value)?.groupValues?.get(1)?.toFloatOrNull()?.let { view.rotation = it }
        SCALE.find(value)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.let { scales ->
            if (scales.isNotEmpty()) {
                view.scaleX = scales[0]
                view.scaleY = scales.getOrElse(1) { scales[0] }
            }
        }
    }

    private fun applyTint(view: View, value: String) {
        if (value.equals("none", true)) {
            if (view is ImageView) view.imageTintList = null else view.backgroundTintList = null
            return
        }
        parseColor(view, value)?.let {
            if (view is ImageView) view.imageTintList = ColorStateList.valueOf(it)
            else view.backgroundTintList = ColorStateList.valueOf(it)
        }
    }

    private fun applyColorFilter(view: View, value: String) {
        if (value.equals("none", true)) {
            if (view is ImageView) view.clearColorFilter() else view.background?.clearColorFilter()
            return
        }
        val tokens = value.split(WHITESPACE)
        val color = tokens.firstNotNullOfOrNull { parseColor(view, it) } ?: return
        val mode = tokens.firstNotNullOfOrNull { runCatching { PorterDuff.Mode.valueOf(it.uppercase()) }.getOrNull() }
            ?: PorterDuff.Mode.SRC_ATOP
        @Suppress("DEPRECATION")
        if (view is ImageView) view.setColorFilter(color, mode) else view.background?.setColorFilter(color, mode)
    }

    private fun applyTextDecoration(view: TextView, value: String) {
        var flags = view.paintFlags
        if (value.contains("none", true)) flags = flags and Paint.UNDERLINE_TEXT_FLAG.inv() and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        if (value.contains("underline", true)) flags = flags or Paint.UNDERLINE_TEXT_FLAG
        if (value.contains("line-through", true)) flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
        view.paintFlags = flags
    }

    private fun applyBoxValues(view: View, value: String, margin: Boolean): Boolean {
        val values = value.split(WHITESPACE).filter(String::isNotBlank).mapNotNull { parseDimension(view, it) }
        if (values.size !in setOf(1, 2, 3, 4)) return false
        val top = values[0]
        val right = if (values.size == 1) top else values[1]
        val bottom = when (values.size) { 1, 2 -> top; else -> values[2] }
        val left = when (values.size) { 1 -> top; 2, 3 -> right; else -> values[3] }
        if (margin) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
            params.setMargins(left, top, right, bottom)
        } else view.setPadding(left, top, right, bottom)
        return true
    }

    private fun applySingleMargin(view: View, property: String, value: String): Boolean {
        val amount = parseDimension(view, value) ?: return false
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        when (property) {
            "margin-left" -> params.leftMargin = amount
            "margin-top" -> params.topMargin = amount
            "margin-right" -> params.rightMargin = amount
            "margin-bottom" -> params.bottomMargin = amount
        }
        return true
    }

    private fun applySinglePadding(view: View, property: String, value: String) {
        val amount = parseDimension(view, value) ?: return
        view.setPadding(
            if (property == "padding-left") amount else view.paddingLeft,
            if (property == "padding-top") amount else view.paddingTop,
            if (property == "padding-right") amount else view.paddingRight,
            if (property == "padding-bottom") amount else view.paddingBottom
        )
    }

    private fun resolveDimension(view: View, value: String, horizontal: Boolean): Int? {
        val normalized = value.trim().lowercase()
        return when (normalized) {
            "match-parent", "match_parent", "100%" -> ViewGroup.LayoutParams.MATCH_PARENT
            "wrap-content", "wrap_content", "auto" -> ViewGroup.LayoutParams.WRAP_CONTENT
            else -> if (normalized.endsWith('%')) {
                val percent = normalized.dropLast(1).toFloatOrNull() ?: return null
                val parent = view.parent as? View
                (((if (horizontal) parent?.width else parent?.height) ?: 0) * percent / 100f).roundToInt()
            } else parseDimension(view, normalized)
        }
    }

    private fun parseDimension(view: View, raw: String): Int? {
        val value = raw.trim().lowercase().substringBefore(' ')
        val density = view.resources.displayMetrics.density
        val scaledDensity = density * view.resources.configuration.fontScale
        return when {
            value.endsWith("dp") || value.endsWith("dip") -> value.takeWhile { it.isDigit() || it in ".-" }.toFloatOrNull()?.times(density)?.roundToInt()
            value.endsWith("sp") -> value.dropLast(2).toFloatOrNull()?.times(scaledDensity)?.roundToInt()
            value.endsWith("px") -> value.dropLast(2).toFloatOrNull()?.times(density)?.roundToInt()
            else -> value.toFloatOrNull()?.roundToInt()
        }
    }

    private fun parseColor(view: View, raw: String): Int? {
        val value = raw.trim().trimEnd(';')
        runCatching { Color.parseColor(value) }.getOrNull()?.let { return it }
        val resourceName = value.removePrefix("color_")
        val id = view.resources.getIdentifier(resourceName, "color", "android")
        return if (id != 0) runCatching { view.context.getColor(id) }.getOrNull() else null
    }

    private fun parseLinearGradient(view: View, value: String): GradientDrawable? {
        val match = LINEAR_GRADIENT.matchEntire(value.trim()) ?: return null
        val parts = match.groupValues[1].split(',').map(String::trim)
        if (parts.size < 2) return null
        val angle = parts.first().removeSuffix("deg").toIntOrNull() ?: 0
        val colors = parts.drop(if (parts.first().endsWith("deg")) 1 else 0)
            .mapNotNull { parseColor(view, it.substringBefore(' ')) }
        if (colors.size < 2) return null
        val orientation = when (((angle % 360) + 360) % 360) {
            in 23..67 -> GradientDrawable.Orientation.BL_TR
            in 68..112 -> GradientDrawable.Orientation.BOTTOM_TOP
            in 113..157 -> GradientDrawable.Orientation.BR_TL
            in 158..202 -> GradientDrawable.Orientation.RIGHT_LEFT
            in 203..247 -> GradientDrawable.Orientation.TR_BL
            in 248..292 -> GradientDrawable.Orientation.TOP_BOTTOM
            in 293..337 -> GradientDrawable.Orientation.TL_BR
            else -> GradientDrawable.Orientation.LEFT_RIGHT
        }
        return GradientDrawable(orientation, colors.toIntArray())
    }

    private fun parseRules(css: String): List<StyleRule> {
        val cleanCss = COMMENTS.replace(css, "")
        val blocks = RULE.findAll(cleanCss).toList()
        // `:root { --accent: #ff5500; }` defines variables usable anywhere as var(--accent).
        val variables = HashMap<String, String>()
        blocks.filter { it.groupValues[1].trim() == ":root" }.forEach { match ->
            parseDeclarations(match.groupValues[2]).forEach { (key, value) ->
                if (key.startsWith("--")) variables[key] = value
            }
        }
        return blocks.asSequence().filter { it.groupValues[1].trim() != ":root" }.flatMap { match ->
            val declarations = parseDeclarations(match.groupValues[2]).mapValues { (_, v) -> resolveVariables(v, variables) }
            match.groupValues[1].split(',').asSequence().mapNotNull { rawSelector ->
                parseSelector(rawSelector.trim())?.let { StyleRule(it, declarations) }
            }
        }.toList()
    }

    private fun resolveVariables(value: String, variables: Map<String, String>): String {
        var resolved = value
        repeat(4) { // bounded depth: variables may reference other variables, but never loop forever
            if (!resolved.contains("var(", ignoreCase = true)) return resolved
            resolved = VAR.replace(resolved) { match ->
                variables[match.groupValues[1].lowercase()] ?: match.groupValues[2].trim()
            }
        }
        return resolved
    }

    private fun parseDeclarations(block: String): Map<String, String> = linkedMapOf<String, String>().apply {
        block.split(';').forEach { raw ->
            val separator = raw.indexOf(':')
            if (separator > 0) put(raw.substring(0, separator).trim().lowercase(), raw.substring(separator + 1).trim())
        }
    }

    private fun parseSelector(raw: String): ViewSelector? {
        if (raw.isBlank()) return null
        var selector = raw
        var activityClass: String? = null
        if (selector.startsWith('.')) {
            val firstSpace = selector.indexOf(' ')
            if (firstSpace > 1) {
                activityClass = selector.substring(1, firstSpace).replace('_', '.')
                selector = selector.substring(firstSpace + 1).trim()
            }
        }
        val contains = CONTAINS.find(selector)?.groupValues?.get(1)?.trim('"', '\'', ' ')
        selector = CONTAINS.replace(selector, "")
        val nthChild = NTH_CHILD.find(selector)?.groupValues?.get(1)?.toIntOrNull()
        selector = NTH_CHILD.replace(selector, "").trim()
        val target = selector.substringAfterLast(' ').trim()
        val ancestorIds = selector.substringBeforeLast(' ', "").split(WHITESPACE)
            .mapNotNull { it.takeIf(String::isNotBlank)?.removePrefix("#") }
        return when {
            target.startsWith('#') -> ViewSelector(activityClass, target.removePrefix("#"), null, contains, nthChild, ancestorIds)
            target.isNotBlank() -> ViewSelector(activityClass, null, target.substringAfterLast('.'), contains, nthChild, ancestorIds)
            else -> null
        }
    }

    private data class StyleRule(val selector: ViewSelector, val declarations: Map<String, String>)

    private data class ViewSelector(
        val activityClass: String?,
        val idName: String?,
        val typeName: String?,
        val containsText: String?,
        val nthChild: Int?,
        val ancestorIds: List<String>
    ) {
        fun matches(view: View, activity: Activity?): Boolean {
            if (activityClass != null && activity?.javaClass?.name != activityClass) return false
            if (idName != null && resourceName(view) != idName && androidResourceName(view) != idName) return false
            if (typeName != null && !matchesType(view, typeName)) return false
            if (containsText != null && (view as? TextView)?.text?.toString()?.contains(containsText, ignoreCase = true) != true) return false
            if (nthChild != null) {
                val parent = view.parent as? ViewGroup ?: return false
                if (parent.indexOfChild(view) + 1 != nthChild) return false
            }
            if (ancestorIds.isNotEmpty()) {
                var parent = view.parent as? View
                for (expected in ancestorIds.asReversed()) {
                    while (parent != null && resourceName(parent) != expected && androidResourceName(parent) != expected) {
                        parent = parent.parent as? View
                    }
                    if (parent == null) return false
                    parent = parent.parent as? View
                }
            }
            return true
        }

        private fun matchesType(view: View, expected: String): Boolean {
            var type: Class<*>? = view.javaClass
            while (type != null) {
                if (type.simpleName.equals(expected, true) || type.name.equals(expected, true)) return true
                type = type.superclass
            }
            return false
        }

        private fun resourceName(view: View): String? = if (view.id == View.NO_ID) null else
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

        private fun androidResourceName(view: View): String? = if (view.id == View.NO_ID) null else
            runCatching { "android_${view.resources.getResourceEntryName(view.id)}" }
                .getOrNull()?.takeIf { runCatching { view.resources.getResourcePackageName(view.id) }.getOrNull() == "android" }
    }

    private companion object {
        const val CSS_KEY = "css_theme"
        const val LEGACY_CSS_KEY = "custom_css"
        const val THEME_KEY = "active_theme"
        const val MAX_ASSET_EDGE = 2048
        val URL = Regex("(?i)url\\(([^)]*)\\)")
        val VAR = Regex("(?i)var\\(\\s*(--[a-z0-9_-]+)\\s*(?:,([^)]*))?\\)")
        val COMMENTS = Regex("(?s)/\\*.*?\\*/")
        val RULE = Regex("(?s)([^{}]+)\\{([^{}]*)\\}")
        val CONTAINS = Regex("(?i):contains\\(([^)]*)\\)")
        val NTH_CHILD = Regex("(?i):nth-child\\((\\d+)\\)")
        val LINEAR_GRADIENT = Regex("(?i)linear-gradient\\((.*)\\)")
        val ROTATE = Regex("(?i)rotate\\((-?[0-9.]+)deg\\)")
        val SCALE = Regex("(?i)scale\\(([^)]+)\\)")
        val WHITESPACE = Regex("\\s+")
    }
}
