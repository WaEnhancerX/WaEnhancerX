package com.waenhancer.xposed.features.customization

import android.R as AndroidR
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.waenhancer.theme.CssThemeRules
import com.waenhancer.theme.CssThemeRules.Part
import com.waenhancer.theme.CssThemeRules.Pseudo
import com.waenhancer.theme.ThemeCssValidator
import com.waenhancer.theme.ThemeIds
import com.waenhancer.xposed.bridge.client.HookProviderIpc
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.ViewHookDispatcher
import com.waenhancer.xposed.utils.ActivityTracker
import com.waenhancer.xposed.utils.HostResourceIds
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Applies theme CSS to WhatsApp views, following WaEnhancer's CustomView model: rules are
 * compiled once into an index keyed by resolved view id (and leaf widget type), so a view
 * attaching to the window costs one map lookup, and styles WhatsApp would otherwise reset
 * (visibility, image backgrounds and drawables) are pinned until the theme changes.
 */
class CssThemeHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String = "Custom Theme CSS"

    private class CompiledPart(val part: Part, val id: Int)

    private class CompiledRule(
        val order: Int,
        val activityClass: String?,
        val parts: List<CompiledPart>,
        val declarations: Map<String, String>
    )

    private class RuleIndex(
        val byId: Map<Int, List<CompiledRule>>,
        val byType: Map<String, List<CompiledRule>>
    ) {
        val isEmpty: Boolean get() = byId.isEmpty() && byType.isEmpty()
    }

    @Volatile
    private var index = RuleIndex(emptyMap(), emptyMap())

    @Volatile
    private var themeId: String? = null

    /** Bumped on every reload; activities older than it get one full re-walk when resumed. */
    @Volatile
    private var generation = 0
    private val appliedGeneration = Collections.synchronizedMap(WeakHashMap<Activity, Int>())

    private val forcedVisibility = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val forcedBackground = Collections.synchronizedMap(WeakHashMap<View, Drawable>())
    private val forcedImage = Collections.synchronizedMap(WeakHashMap<ImageView, Drawable>())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val assetCache = LruCache<String, Bitmap>(24)
    private val missingAssets = Collections.synchronizedSet(HashSet<String>())
    private val activityClasses = ConcurrentHashMap<String, Any>()

    @Volatile
    private var drawableHooksInstalled = false

    // Held in a field: SharedPreferences implementations may keep listeners weakly.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && THEME_KEYS.any { key == it || key.endsWith(":$it") }) {
            reloadRules()
            mainHandler.post { ActivityTracker.getCurrentActivity()?.let(::applyToActivity) }
        }
    }

    override fun hook() {
        reloadRules()

        ViewHookDispatcher.onAttached(this) { view -> applyRules(view) }
        ViewHookDispatcher.onSetFlags(this) { view, flags, mask ->
            if (mask and VISIBILITY_MASK == 0) return@onSetFlags flags
            val forced = forcedVisibility[view] ?: return@onSetFlags flags
            (flags and VISIBILITY_MASK.inv()) or forced
        }

        ActivityTracker.addListener { activity ->
            if ((appliedGeneration[activity] ?: -1) < generation) applyToActivity(activity)
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    // ---------------------------------------------------------------------------------------
    // Rule loading
    // ---------------------------------------------------------------------------------------

    private fun reloadRules() {
        forcedVisibility.clear()
        forcedBackground.clear()
        forcedImage.clear()
        assetCache.evictAll()
        missingAssets.clear()
        themeId = getStringPref(THEME_KEY, null)?.takeIf(ThemeIds::isValidId)
        // An explicit (even empty) css_theme wins; the legacy key only serves installs that never set it.
        val css = (if (hasPref(CSS_KEY)) getStringPref(CSS_KEY, "") else getStringPref(LEGACY_CSS_KEY, "")).orEmpty()
        index = runCatching { compile(css) }
            .onFailure { XposedBridge.log("WAEX: invalid custom CSS: ${it.message}") }
            .getOrDefault(RuleIndex(emptyMap(), emptyMap()))
        generation++
    }

    private fun hasPref(key: String): Boolean =
        prefs.contains(key) || context.packageName?.let { prefs.contains("$it:$key") } == true

    private fun compile(css: String): RuleIndex {
        if (css.isBlank()) return RuleIndex(emptyMap(), emptyMap())
        val validation = ThemeCssValidator.validate(css)
        if (!validation.valid) {
            XposedBridge.log("WAEX: theme CSS rejected: ${validation.errors.joinToString()}")
            return RuleIndex(emptyMap(), emptyMap())
        }
        val byId = HashMap<Int, MutableList<CompiledRule>>()
        val byType = HashMap<String, MutableList<CompiledRule>>()
        var unresolved = 0
        CssThemeRules.parse(css).forEach { rule ->
            val parts = rule.parts.map { part ->
                CompiledPart(part, if (part is Part.Id) resolveId(part.name) else 0)
            }
            // A selector naming an id this WhatsApp build lacks can never match.
            if (parts.any { it.part is Part.Id && it.id == 0 }) {
                unresolved++
                return@forEach
            }
            val compiled = CompiledRule(rule.order, rule.activityClass, parts, rule.declarations)
            val leaf = parts.last()
            when (val part = leaf.part) {
                is Part.Id -> byId.getOrPut(leaf.id) { ArrayList() } += compiled
                is Part.Type -> byType.getOrPut(part.name.lowercase()) { ArrayList() } += compiled
            }
        }
        if (unresolved > 0) XposedBridge.log("WAEX: $unresolved theme CSS rule(s) target ids missing from this WhatsApp build")
        return RuleIndex(byId, byType)
    }

    private fun resolveId(name: String): Int =
        if (name.startsWith(ANDROID_PREFIX)) {
            Resources.getSystem().getIdentifier(name.removePrefix(ANDROID_PREFIX), "id", "android")
        } else {
            HostResourceIds.id(context, name)
        }

    // ---------------------------------------------------------------------------------------
    // Matching
    // ---------------------------------------------------------------------------------------

    private fun applyToActivity(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        appliedGeneration[activity] = generation
        applyRecursively(activity.window?.peekDecorView())
    }

    private fun applyRecursively(view: View?) {
        if (view == null) return
        applyRules(view)
        if (view is ViewGroup) {
            for (child in 0 until view.childCount) applyRecursively(view.getChildAt(child))
        }
    }

    private fun applyRules(view: View) {
        val current = index
        if (current.isEmpty) return
        val byId = if (view.id != View.NO_ID) current.byId[view.id] else null
        val byType = if (current.byType.isEmpty()) null else {
            typeNames(view.javaClass).flatMap { current.byType[it].orEmpty() }.takeIf { it.isNotEmpty() }
        }
        val candidates = when {
            byId == null && byType == null -> return
            byType == null -> byId!!
            byId == null -> byType
            else -> (byId + byType).sortedBy { it.order }
        }
        val activity = activityOf(view)
        candidates.forEach { rule ->
            if (!matchesActivity(rule, activity)) return@forEach
            if (matchesFrom(view, rule.parts, rule.parts.lastIndex)) {
                runCatching { applyDeclarations(view, rule.declarations) }
                    .onFailure { XposedBridge.log("WAEX: theme CSS rule failed: ${it.message}") }
            }
        }
    }

    /**
     * True when [view] satisfies compound [k] and some ancestor chain satisfies the compounds
     * before it, mirroring WaEnhancer's top-down capture walked bottom-up.
     */
    private fun matchesFrom(view: View, parts: List<CompiledPart>, k: Int): Boolean {
        val part = parts[k]
        if (!matchesLocal(view, part)) return false
        if (k == 0) return true
        when (val selector = part.part) {
            // findViewById semantics: the previous compound is this view or any ancestor.
            is Part.Id -> {
                var candidate: View? = view
                while (candidate != null) {
                    if (matchesFrom(candidate, parts, k - 1)) return true
                    candidate = candidate.parent as? View
                }
            }
            // Direct child; framework widget names also look through non-matching containers.
            is Part.Type -> {
                val flatten = isFrameworkWidget(selector.name)
                var candidate = view.parent as? View
                while (candidate != null) {
                    if (matchesFrom(candidate, parts, k - 1)) return true
                    if (!flatten || matchesType(candidate.javaClass, selector.name)) return false
                    candidate = candidate.parent as? View
                }
            }
        }
        return false
    }

    private fun matchesLocal(view: View, compiled: CompiledPart): Boolean {
        val part = compiled.part
        when (part) {
            is Part.Id -> if (view.id != compiled.id) return false
            is Part.Type -> if (!matchesType(view.javaClass, part.name)) return false
        }
        part.pseudos.forEach { pseudo ->
            when (pseudo) {
                is Pseudo.Contains ->
                    if ((view as? TextView)?.text?.contains(pseudo.text, ignoreCase = true) != true) return false
                is Pseudo.NthChild -> if (childPosition(view, part) != pseudo.position) return false
            }
        }
        return true
    }

    /** 1-based position among same-type siblings (type compounds) or all siblings (id compounds). */
    private fun childPosition(view: View, part: Part): Int {
        val parent = view.parent as? ViewGroup ?: return -1
        var position = 0
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChildAt(i)
            if (part is Part.Type && !matchesType(sibling.javaClass, part.name)) continue
            position++
            if (sibling === view) return position
        }
        return -1
    }

    /** Simple names a type selector can match: the class and its supers up to the first framework class. */
    private fun typeNames(type: Class<*>): Set<String> = TYPE_NAMES.getOrPut(type) {
        val names = HashSet<String>()
        var current: Class<*>? = type
        while (current != null) {
            names += current.simpleName.lowercase()
            if (current.name.startsWith("android.widget.") || current.name.startsWith("android.view.")) break
            current = current.superclass
        }
        names
    }

    private fun matchesType(type: Class<*>, name: String): Boolean = name.lowercase() in typeNames(type)

    private fun isFrameworkWidget(name: String): Boolean = FRAMEWORK_WIDGETS.getOrPut(name) {
        runCatching { Class.forName("android.widget.$name") }.isSuccess
    }

    private fun matchesActivity(rule: CompiledRule, activity: Activity?): Boolean {
        val name = rule.activityClass ?: return true
        if (activity == null) return false
        val type = activityClasses.getOrPut(name) {
            XposedHelpers.findClassIfExists(name, classLoader) ?: MISSING_CLASS
        }
        return type is Class<*> && type.isInstance(activity)
    }

    private fun activityOf(view: View): Activity? {
        var context: Context? = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return ActivityTracker.getCurrentActivity()
    }

    // ---------------------------------------------------------------------------------------
    // Declarations
    // ---------------------------------------------------------------------------------------

    private fun applyDeclarations(view: View, declarations: Map<String, String>) {
        var requestLayout = false
        declarations.forEach { (property, value) ->
            runCatching {
                when (property) {
                    "display", "visibility" -> applyVisibility(view, value)
                    "alpha", "opacity" -> value.toFloatOrNull()?.let { view.alpha = it.coerceIn(0f, 1f) }
                    "background", "background-image" -> applyBackground(view, value, declarations)
                    "background-color" -> applyBackgroundColor(view, value)
                    "background-size" -> applyBackgroundSize(view, value)
                    "foreground" -> applyForeground(view, value, declarations)
                    "width" -> view.layoutParams?.let { params ->
                        resolveDimension(view, value, true)?.let { params.width = it; requestLayout = true }
                    }
                    "height" -> view.layoutParams?.let { params ->
                        resolveDimension(view, value, false)?.let { params.height = it; requestLayout = true }
                    }
                    "margin" -> if (applyBoxValues(view, value, margin = true)) requestLayout = true
                    "padding" -> applyBoxValues(view, value, margin = false)
                    "margin-left", "margin-top", "margin-right", "margin-bottom",
                    "left", "top", "right", "bottom" -> if (applySingleMargin(view, property, value)) requestLayout = true
                    "padding-left", "padding-top", "padding-right", "padding-bottom" ->
                        applySinglePadding(view, property, value)
                    "border-radius" -> applyRoundedBackground(view, value)
                    "border" -> applyBorder(view, value)
                    "box-shadow", "elevation" -> value.split(WHITESPACE)
                        .firstNotNullOfOrNull { token -> parseDimension(view, token)?.takeIf { it > 0 } }
                        ?.let { view.elevation = it.toFloat() }
                    "rotation" -> value.removeSuffix("deg").toFloatOrNull()?.let { view.rotation = it }
                    "transform" -> applyTransform(view, value)
                    "color-tint" -> applyTint(view, value)
                    "color-filter" -> applyColorFilter(view, value)
                    "color" -> if (view is TextView) parseColor(view, value)?.let(view::setTextColor)
                    "font-size" -> if (view is TextView) applyFontSize(view, value)
                    "font-weight" -> if (view is TextView) {
                        val bold = value.lowercase() in BOLD_WEIGHTS
                        view.setTypeface(Typeface.create(view.typeface, if (bold) Typeface.BOLD else Typeface.NORMAL))
                    }
                    "font-style" -> if (view is TextView) {
                        val italic = value.equals("italic", true) || value.equals("oblique", true)
                        view.setTypeface(Typeface.create(view.typeface, if (italic) Typeface.ITALIC else Typeface.NORMAL))
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

    private fun applyVisibility(view: View, value: String) {
        val visibility = when (value.lowercase()) {
            "none", "gone" -> View.GONE
            "invisible", "hidden" -> View.INVISIBLE
            "block", "visible", "flex", "inline" -> View.VISIBLE
            else -> return
        }
        forcedVisibility[view] = visibility
        view.visibility = visibility
    }

    private fun applyBackground(view: View, value: String, declarations: Map<String, String>) {
        if (value.equals("none", true) || value.equals("transparent", true)) {
            forcedBackground.remove(view)
            view.background = null
            return
        }
        parseLinearGradient(view, value)?.let { pinBackground(view, it); return }
        parseUrlDrawable(view, value, declarations)?.let { pinDrawable(view, it); return }
        parseColor(view, value.split(WHITESPACE).first())?.let(view::setBackgroundColor)
    }

    /** One color paints the background; `#old #new` recolors matching colors in the subtree. */
    private fun applyBackgroundColor(view: View, value: String) {
        val colors = value.split(WHITESPACE).filter(String::isNotBlank).mapNotNull { parseColor(view, it) }
        when (colors.size) {
            1 -> view.setBackgroundColor(colors[0])
            2 -> {
                replaceColors(view, colors[0], colors[1])
                if (view is ImageView) view.drawable?.let { it.mutate().setTint(colors[1]); view.invalidate() }
            }
        }
    }

    private fun applyBackgroundSize(view: View, value: String) {
        if (view !is ImageView) return // non-image backgrounds honour it inside url() drawables
        when (value.lowercase()) {
            "cover" -> view.scaleType = ImageView.ScaleType.CENTER_CROP
            "contain", "100%", "100% 100%" -> view.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun applyForeground(view: View, value: String, declarations: Map<String, String>) {
        if (value.equals("none", true)) {
            view.foreground = null
            return
        }
        (parseUrlDrawable(view, value, declarations) ?: parseColor(view, value)?.let { ColorDrawable(it) })
            ?.let { view.foreground = it }
    }

    /** Image views take the picture as their content, like WaEnhancer; others as background. */
    private fun pinDrawable(view: View, drawable: Drawable) {
        if (view is ImageView) {
            ensureDrawableHooks()
            forcedImage[view] = drawable
            view.setImageDrawable(drawable)
        } else {
            pinBackground(view, drawable)
        }
    }

    private fun pinBackground(view: View, drawable: Drawable) {
        ensureDrawableHooks()
        forcedBackground[view] = drawable
        view.background = drawable
    }

    /** Stops WhatsApp from overwriting themed backgrounds and images when it rebinds a view. */
    @Synchronized
    private fun ensureDrawableHooks() {
        if (drawableHooksInstalled) return
        drawableHooksInstalled = true
        retainHook(XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", Drawable::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (forcedBackground.isEmpty()) return
                    val forced = forcedBackground[param.thisObject] ?: return
                    if (param.args[0] !== forced) param.result = null
                }
            }))
        val blockImage = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (forcedImage.isEmpty()) return
                val forced = forcedImage[param.thisObject] ?: return
                if (param.args[0] !== forced) param.result = null
            }
        }
        retainHook(XposedHelpers.findAndHookMethod(ImageView::class.java, "setImageDrawable", Drawable::class.java, blockImage))
        retainHook(XposedHelpers.findAndHookMethod(ImageView::class.java, "setImageResource", Int::class.javaPrimitiveType, blockImage))
        retainHook(XposedHelpers.findAndHookMethod(ImageView::class.java, "setImageURI", Uri::class.java, blockImage))
    }

    /** Resolves `url('file.png')` against the active theme's bundled assets, honouring `background-size`. */
    private fun parseUrlDrawable(view: View, value: String, declarations: Map<String, String>?): Drawable? {
        val name = URL.find(value)?.groupValues?.get(1)?.trim('"', '\'', ' ') ?: return null
        val bitmap = loadAsset(name.substringAfterLast('/')) ?: return null
        val size = declarations?.get("background-size")?.trim()?.lowercase()
        val (width, height) = assetSize(view, size, bitmap)
        return AssetDrawable(
            BitmapDrawable(view.resources, bitmap),
            cover = size == "cover", contain = size == "contain",
            width = width, height = height
        )
    }

    /**
     * Intrinsic size for a theme image. `background-size: 77px 40px` sizes it like WaEnhancer
     * (CSS px as dp; one length keeps the aspect ratio). Otherwise it takes the footprint of the
     * drawable it replaces, so a large source bitmap never inflates a wrap-content view.
     */
    private fun assetSize(view: View, size: String?, bitmap: Bitmap): Pair<Int, Int> {
        val lengths = size?.split(WHITESPACE)?.filter(String::isNotBlank).orEmpty()
        if (lengths.isNotEmpty() && lengths.size <= 2) {
            val width = sizeLength(view, lengths[0], horizontal = true)
            val height = lengths.getOrNull(1)?.let { sizeLength(view, it, horizontal = false) }
            if (width != null && width > 0) {
                val resolvedHeight = height?.takeIf { it > 0 }
                    ?: (width.toFloat() * bitmap.height / bitmap.width.coerceAtLeast(1)).roundToInt()
                return width to resolvedHeight
            }
        }
        val replaced = if (view is ImageView) view.drawable else view.background
        val width = replaced?.intrinsicWidth?.takeIf { it > 0 } ?: view.width.takeIf { it > 0 } ?: -1
        val height = replaced?.intrinsicHeight?.takeIf { it > 0 } ?: view.height.takeIf { it > 0 } ?: -1
        return width to height
    }

    private fun sizeLength(view: View, token: String, horizontal: Boolean): Int? {
        if (token == "auto") return null
        if (token.endsWith('%')) {
            val percent = token.dropLast(1).toFloatOrNull() ?: return null
            val base = if (horizontal) view.width else view.height
            return if (base > 0) (base * percent / 100f).roundToInt() else null
        }
        return parseDimension(view, token)
    }

    private fun loadAsset(name: String): Bitmap? {
        val id = themeId ?: return null
        if (!ThemeIds.isValidAssetName(name)) return null
        val key = "$id/$name"
        assetCache.get(key)?.let { return it }
        if (key in missingAssets) return null
        val bitmap = runCatching {
            val result = HookProviderIpc.call(context, "theme_asset", id, Bundle().apply { putString("name", name) })
            @Suppress("DEPRECATION")
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
    private class AssetDrawable(
        private val bitmap: BitmapDrawable,
        private val cover: Boolean,
        private val contain: Boolean,
        private val width: Int,
        private val height: Int
    ) : Drawable() {
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
        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
        override fun setAlpha(alpha: Int) { bitmap.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { bitmap.colorFilter = colorFilter }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---------------------------------------------------------------------------------------
    // Color replacement (`background-color: #old #new`)
    // ---------------------------------------------------------------------------------------

    private fun replaceColors(view: View, from: Int, to: Int) {
        replaceDrawableColor(view.background, from, to)
        when (view) {
            is TextView -> {
                val current = view.currentTextColor
                if (colorMatches(current, from)) view.setTextColor(replacement(current, from, to))
            }
            is ImageView -> {
                val filter = view.colorFilter
                if (filter is PorterDuffColorFilter) {
                    val current = runCatching { XposedHelpers.callMethod(filter, "getColor") as Int }.getOrNull()
                    if (current != null && colorMatches(current, from)) view.setColorFilter(replacement(current, from, to))
                }
            }
            is ViewGroup -> for (i in 0 until view.childCount) replaceColors(view.getChildAt(i), from, to)
        }
    }

    private fun replaceDrawableColor(drawable: Drawable?, from: Int, to: Int) {
        when (drawable) {
            null -> return
            is ColorDrawable -> if (colorMatches(drawable.color, from)) drawable.color = replacement(drawable.color, from, to)
            is GradientDrawable -> {
                drawable.colors?.let { colors ->
                    var changed = false
                    for (i in colors.indices) if (colorMatches(colors[i], from)) {
                        colors[i] = replacement(colors[i], from, to)
                        changed = true
                    }
                    if (changed) drawable.colors = colors
                } ?: drawable.color?.let { list ->
                    val current = list.defaultColor
                    if (colorMatches(current, from)) drawable.setColor(replacement(current, from, to))
                }
            }
            is ShapeDrawable -> {
                val current = drawable.paint.color
                if (colorMatches(current, from)) drawable.paint.color = replacement(current, from, to)
            }
            is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) replaceDrawableColor(drawable.getDrawable(i), from, to)
            is DrawableContainer -> (drawable.constantState as? DrawableContainer.DrawableContainerState)
                ?.children?.forEach { replaceDrawableColor(it, from, to) }
            is DrawableWrapper -> replaceDrawableColor(drawable.drawable, from, to)
        }
    }

    /** An opaque `from` matches any alpha of that RGB; a translucent `from` must match exactly. */
    private fun colorMatches(color: Int, from: Int): Boolean =
        if (Color.alpha(from) == 0xFF) (color and 0xFFFFFF) == (from and 0xFFFFFF) else color == from

    private fun replacement(color: Int, from: Int, to: Int): Int =
        if (Color.alpha(from) == 0xFF && Color.alpha(to) == 0xFF) (color and 0xFF000000.toInt()) or (to and 0xFFFFFF) else to

    // ---------------------------------------------------------------------------------------
    // Shapes, transforms and text
    // ---------------------------------------------------------------------------------------

    private fun applyRoundedBackground(view: View, value: String) {
        val radius = parseDimension(view, value)?.toFloat() ?: return
        val existing = view.background
        if (existing is GradientDrawable) {
            existing.mutate()
            existing.cornerRadius = radius
            return
        }
        val currentColor = (existing as? ColorDrawable)?.color ?: Color.TRANSPARENT
        view.background = GradientDrawable().apply { setColor(currentColor); cornerRadius = radius }
    }

    private fun applyBorder(view: View, value: String) {
        val tokens = value.split(WHITESPACE).filter(String::isNotBlank)
        val width = tokens.firstNotNullOfOrNull { parseDimension(view, it) } ?: return
        val color = tokens.firstNotNullOfOrNull { parseColor(view, it) } ?: return
        val drawable = view.background as? GradientDrawable ?: GradientDrawable().apply {
            setColor((view.background as? ColorDrawable)?.color ?: Color.TRANSPARENT)
        }
        drawable.mutate()
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

    /** One color tints; three colors are default, pressed and disabled states (WaEnhancer order). */
    private fun applyTint(view: View, value: String) {
        val list = if (value.equals("none", true)) null else {
            val colors = value.split(WHITESPACE).mapNotNull { parseColor(view, it) }
            when {
                colors.isEmpty() -> return
                colors.size < 3 -> ColorStateList.valueOf(colors[0])
                else -> ColorStateList(
                    arrayOf(
                        intArrayOf(AndroidR.attr.state_pressed),
                        intArrayOf(-AndroidR.attr.state_enabled),
                        intArrayOf()
                    ),
                    intArrayOf(colors[1], colors[2], colors[0])
                )
            }
        }
        if (view is ImageView) view.imageTintList = list else view.backgroundTintList = list
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
        if (view is ImageView) view.setColorFilter(color, mode)
        else view.background?.colorFilter = PorterDuffColorFilter(color, mode)
    }

    /** Unitless sizes are sp, like WaEnhancer's `setTextSize(float)`. */
    private fun applyFontSize(view: TextView, value: String) {
        val number = value.trim().lowercase()
        number.toFloatOrNull()?.let { view.setTextSize(TypedValue.COMPLEX_UNIT_SP, it); return }
        parseDimension(view, number)?.let { view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.toFloat()) }
    }

    private fun applyTextDecoration(view: TextView, value: String) {
        var flags = view.paintFlags
        if (value.contains("none", true)) flags = flags and Paint.UNDERLINE_TEXT_FLAG.inv() and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        if (value.contains("underline", true)) flags = flags or Paint.UNDERLINE_TEXT_FLAG
        if (value.contains("line-through", true)) flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
        view.paintFlags = flags
    }

    // ---------------------------------------------------------------------------------------
    // Box model and units
    // ---------------------------------------------------------------------------------------

    private fun applyBoxValues(view: View, value: String, margin: Boolean): Boolean {
        val values = value.split(WHITESPACE).filter(String::isNotBlank).mapNotNull { parseDimension(view, it) }
        if (values.size !in 1..4) return false
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
        when (property.removePrefix("margin-")) {
            "left" -> params.leftMargin = amount
            "top" -> params.topMargin = amount
            "right" -> params.rightMargin = amount
            "bottom" -> params.bottomMargin = amount
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

    /** `dp` and `px` are density-independent (WaEnhancer treats CSS px as dp); unitless is raw pixels. */
    private fun parseDimension(view: View, raw: String): Int? {
        val value = raw.trim().lowercase().substringBefore(' ')
        val metrics = view.resources.displayMetrics
        val numeric = value.takeWhile { it.isDigit() || it == '.' || it == '-' }
        val number = numeric.toFloatOrNull() ?: return null
        return when (value.substring(numeric.length)) {
            "dp", "dip", "px" -> (number * metrics.density).roundToInt()
            "sp" -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, number, metrics).roundToInt()
            "" -> number.roundToInt()
            else -> null
        }
    }

    private fun parseColor(view: View, raw: String): Int? {
        CssThemeRules.parseColor(raw)?.let { return it }
        // Framework color resources, e.g. `system_accent1_500` or `color_system_accent1_500`.
        val resourceName = raw.trim().trimEnd(';').removePrefix("color_")
        if (!SYSTEM_COLOR_NAME.matches(resourceName)) return null
        val id = SYSTEM_COLOR_IDS.getOrPut(resourceName) { view.resources.getIdentifier(resourceName, "color", "android") }
        return if (id != 0) runCatching { view.context.getColor(id) }.getOrNull() else null
    }

    private fun parseLinearGradient(view: View, value: String): GradientDrawable? {
        val match = LINEAR_GRADIENT.matchEntire(value.trim()) ?: return null
        val parts = splitTopLevel(match.groupValues[1])
        if (parts.size < 2) return null
        val first = parts.first().lowercase()
        val angle = when {
            first.endsWith("deg") -> first.removeSuffix("deg").toFloatOrNull()?.roundToInt() ?: 180
            first.startsWith("to ") -> when (first.removePrefix("to ").trim()) {
                "top" -> 0; "right" -> 90; "bottom" -> 180; "left" -> 270
                "top right", "right top" -> 45; "bottom right", "right bottom" -> 135
                "bottom left", "left bottom" -> 225; "top left", "left top" -> 315
                else -> 180
            }
            else -> null
        }
        val colors = parts.drop(if (angle != null) 1 else 0).mapNotNull { stop -> parseColor(view, stopColor(stop)) }
        if (colors.size < 2) return null
        // CSS angles: 0deg points up and grows clockwise; 180deg (the default) runs top to bottom.
        val orientation = when ((((angle ?: 180) % 360) + 360) % 360) {
            in 23..67 -> GradientDrawable.Orientation.BL_TR
            in 68..112 -> GradientDrawable.Orientation.LEFT_RIGHT
            in 113..157 -> GradientDrawable.Orientation.TL_BR
            in 158..202 -> GradientDrawable.Orientation.TOP_BOTTOM
            in 203..247 -> GradientDrawable.Orientation.TR_BL
            in 248..292 -> GradientDrawable.Orientation.RIGHT_LEFT
            in 293..337 -> GradientDrawable.Orientation.BR_TL
            else -> GradientDrawable.Orientation.BOTTOM_TOP
        }
        return GradientDrawable(orientation, colors.toIntArray())
    }

    /** The color of a gradient stop such as `#fff 40%` or `rgba(0, 0, 0, .5) 10%`. */
    private fun stopColor(stop: String): String {
        val close = stop.indexOf(')')
        return if (stop.startsWith("rgb", ignoreCase = true) && close > 0) stop.substring(0, close + 1)
        else stop.substringBefore(' ')
    }

    /** Splits on commas that are not inside parentheses, so `rgb(1, 2, 3)` stays one stop. */
    private fun splitTopLevel(value: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        value.forEachIndexed { i, c ->
            when (c) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { parts += value.substring(start, i).trim(); start = i + 1 }
            }
        }
        parts += value.substring(start).trim()
        return parts.filter(String::isNotEmpty)
    }

    private companion object {
        const val CSS_KEY = "css_theme"
        const val LEGACY_CSS_KEY = "custom_css"
        const val THEME_KEY = "active_theme"
        val THEME_KEYS = listOf(CSS_KEY, LEGACY_CSS_KEY, THEME_KEY)
        const val ANDROID_PREFIX = "android_"
        const val MAX_ASSET_EDGE = 2048
        const val VISIBILITY_MASK = 0x0000000C
        val MISSING_CLASS = Any()
        val BOLD_WEIGHTS = setOf("bold", "bolder", "600", "700", "800", "900")

        // Class hierarchies and framework names never change within a process.
        val TYPE_NAMES = ConcurrentHashMap<Class<*>, Set<String>>()
        val FRAMEWORK_WIDGETS = ConcurrentHashMap<String, Boolean>()
        val SYSTEM_COLOR_IDS = ConcurrentHashMap<String, Int>()

        val SYSTEM_COLOR_NAME = Regex("[a-z][a-z0-9_]*")
        val URL = Regex("(?i)url\\(([^)]*)\\)")
        val LINEAR_GRADIENT = Regex("(?i)linear-gradient\\((.*)\\)")
        val ROTATE = Regex("(?i)rotate\\((-?[0-9.]+)deg\\)")
        val SCALE = Regex("(?i)scale\\(([^)]+)\\)")
        val WHITESPACE = Regex("\\s+")
    }
}
