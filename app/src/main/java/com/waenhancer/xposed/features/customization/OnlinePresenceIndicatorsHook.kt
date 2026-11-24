package com.waenhancer.xposed.features.customization

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.features.automation.PresenceStateStore
import com.waenhancer.xposed.utils.HookErrors
import com.waenhancer.xposed.utils.HostFields
import com.waenhancer.xposed.utils.HostResourceIds
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Renders online presence indicators (green dot on contact avatar) on conversation rows. */
class OnlinePresenceIndicatorsHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val visibleRows = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val pendingBindings = Collections.synchronizedMap(WeakHashMap<View, Any>())
    private val refreshPending = AtomicBoolean()
    private val showDot = isEnabled("dotonline", false)
    private val showText = isEnabled("showonlinetext", false)
    @Volatile private var subscriptionReceiver: Any? = null
    private var subscriptionMethod: Method? = null
    private val hostJidType: Class<*>? = try {
        classLoader.loadClass("com.whatsapp.infra.core.jid.Jid")
    } catch (_: Throwable) { null }
    private val stringMembers = Collections.synchronizedMap(WeakHashMap<Class<*>, List<Method>>())
    private val accessorCache = Collections.synchronizedMap(WeakHashMap<Class<*>, List<Method>>())

    override val name: String get() = "Online Presence Indicators"

    override fun hook() {
        if (!showDot && !showText) return
        try {
            val bindMethods = resolveRowFillers()
            if (bindMethods.isEmpty()) return

            subscriptionMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, "waex_presence_subscription_sender_v4"
            ) { bridge, loader ->
                val target = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().addUsingString(
                    "app/send-presence-subscription jid=", StringMatchType.Contains
                ))).firstOrNull()
                target?.callers?.firstOrNull()?.getMethodInstance(loader)
                    ?: target?.getMethodInstance(loader)
            }

            subscriptionMethod?.let { method ->
                XposedBridge.hookAllConstructors(method.declaringClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        subscriptionReceiver = param.thisObject
                    }
                })
            }

            val rowHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sources = ArrayList<Any?>(param.args?.asList().orEmpty())
                    param.thisObject?.let { sources.add(it) }
                    val row = findConversationRow(sources) ?: return
                    val jid = findJid(param.args, 3)
                    if (param.hasThrowable() || jid == null || jid.contains("@g.us")) {
                        queueBindRow(row, null)
                        return
                    }
                    subscriptionMethod?.let { method ->
                        findObjectOfType(sources, method.parameterTypes.firstOrNull() ?: Any::class.java)?.let {
                            recordIdentityAlias(
                                PresenceStateStore.normalize(identityOf(it) ?: it.toString()),
                                PresenceStateStore.normalize(jid)
                            )
                        }
                    }
                    val canonical = PresenceStateStore.normalize(jid)
                    val identities = collectJidIdentities(sources)
                    identities.forEach { identity ->
                        if (identity != canonical && !identity.contains("@g.us")) {
                            recordIdentityAlias(identity, canonical)
                        }
                    }
                    requestPresence(sources, jid)
                    queueBindRow(row, canonical)
                }
            }
            bindMethods.forEach { XposedBridge.hookMethod(it, rowHook) }
            PresenceStateStore.addListener { _, _ -> queuePresenceRefresh() }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][OnlinePresence] Setup failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Resolves the methods that populate a conversation-list row, without any signature or
     * obfuscated-name assumption. The host's row filler logs under the "ConversationViewFiller/"
     * prefix; the per-row fill paths ("fillView", "buildCachedDataBundle") run for every row, while
     * other members of that family (for example the parent-group photo setter) only run for a
     * subset of rows. Prefer the former and fall back to the whole family; the hook itself validates
     * every call structurally (row views + JID) so a wrong candidate is harmless.
     */
    private fun resolveRowFillers(): List<Method> {
        val resolved = DexSearchEngine.getInstance().findMethodsWithCache(
            context, classLoader, "waex_conversation_row_fillers_v6"
        ) { bridge, loader ->
            // Bridge/synthetic and static members are valid: the host's fill path is often reached
            // through a compiler-generated bridge or a static trampoline, so only the shape matters.
            fun usable(method: Method) = !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isNative(method.modifiers) &&
                method.returnType == Void.TYPE && method.parameterTypes.size >= 2
            fun query(prefix: String) = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().addUsingString(prefix, StringMatchType.StartsWith)
                )
            ).filter { it.isMethod }.mapNotNull {
                try { it.getMethodInstance(loader) } catch (_: Throwable) { null }
            }.filter(::usable)

            val perRow = (query("ConversationViewFiller/fillView") +
                query("ConversationViewFiller/buildCachedDataBundle")).distinct()
            val chosen = perRow.ifEmpty { query("ConversationViewFiller/").distinct() }
            chosen.toTypedArray()
        }
        return resolved?.toList().orEmpty()
    }

    private fun queuePresenceRefresh() {
        if (!refreshPending.compareAndSet(false, true)) return
        if (!mainHandler.post {
            refreshPending.set(false)
            val snapshot = synchronized(visibleRows) {
                visibleRows.entries.map { it.key to it.value }
            }
            snapshot.forEach { (row, boundJid) ->
                if (visibleRows[row] == boundJid && row.getTag(TAG_BOUND_JID) == boundJid) {
                    render(row, stateForRow(boundJid))
                }
            }
        }) refreshPending.set(false)
    }

    private fun queueBindRow(row: View, jid: String?) {
        val token = Any()
        synchronized(pendingBindings) {
            pendingBindings[row] = token
            visibleRows.remove(row)
        }
        val weakRow = WeakReference(row)
        mainHandler.post {
            val current = weakRow.get() ?: return@post
            synchronized(pendingBindings) {
                if (pendingBindings[current] !== token) return@post
                pendingBindings.remove(current)
                if (jid == null || jid.isEmpty()) clearRow(current) else bindRow(current, jid)
            }
        }
    }

    private fun clearRow(row: View) {
        visibleRows.remove(row)
        row.setTag(TAG_BOUND_JID, null)
        render(row, null)
    }

    private fun stateForRow(jid: String): PresenceStateStore.State? {
        val keys = linkedSetOf(jid)
        identityAliases.entries.forEach { (alias, canonical) ->
            if (canonical == jid) keys.add(alias)
        }
        return PresenceStateStore.getLatest(keys)
    }

    private fun bindRow(row: View, jid: String) {
        ensureDotView(row)
        ensureTextView(row)
        visibleRows[row] = jid
        row.setTag(TAG_BOUND_JID, jid)
        render(row, stateForRow(jid))
    }

    private fun requestPresence(sources: List<Any?>, jid: String?) {
        val method = subscriptionMethod ?: return
        val receiver = subscriptionReceiver
        val key = jid ?: return
        val now = SystemClock.uptimeMillis()
        val previous = subscriptions[key]
        if (previous != null && now - previous < 30_000L) return
        recordSubscription(key, now)

        try {
            val params = method.parameterTypes
            val jidArg = if (params.isNotEmpty()) findObjectOfType(sources, params[0]) else null

            if (jidArg != null) {
                method.isAccessible = true
                if (params.size == 1) {
                    method.invoke(receiver, jidArg)
                } else if (params.size == 4) {
                    val tokenType = params[2]
                    val token = createDefaultInstance(tokenType)
                    method.invoke(receiver, jidArg, null, token, receiver)
                }
            }
        } catch (t: Throwable) {
            if (subscriptionDiagnostics.getAndIncrement() < 5) {
                XposedBridge.log("[WAEX][OnlinePresence] Presence request error: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun createDefaultInstance(type: Class<*>): Any? {
        return try {
            val constructor = type.declaredConstructors.firstOrNull() ?: return null
            constructor.isAccessible = true
            val args = constructor.parameterTypes.map { p ->
                when {
                    !p.isPrimitive -> null
                    p == Boolean::class.javaPrimitiveType -> false
                    p == Byte::class.javaPrimitiveType -> 0.toByte()
                    p == Short::class.javaPrimitiveType -> 0.toShort()
                    p == Int::class.javaPrimitiveType -> 0
                    p == Long::class.javaPrimitiveType -> 0L
                    p == Float::class.javaPrimitiveType -> 0f
                    p == Double::class.javaPrimitiveType -> 0.0
                    p == Char::class.javaPrimitiveType -> '\u0000'
                    else -> null
                }
            }.toTypedArray()
            constructor.newInstance(*args)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findObjectOfType(sources: List<Any?>, wanted: Class<*>): Any? {
        val seen = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        sources.forEach { findObjectOfType(it, wanted, 4, seen)?.let { found -> return found } }
        return null
    }

    private fun findObjectOfType(value: Any?, wanted: Class<*>, depth: Int, seen: MutableSet<Any>): Any? {
        if (value == null || depth < 0 || !seen.add(value)) return null
        if (wanted.isInstance(value)) return value
        var type: Class<*>? = value.javaClass
        while (type != null && !type.name.startsWith("java.") && !type.name.startsWith("android.")) {
            for (field in HostFields.declared(type)) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findObjectOfType(field.get(value), wanted, depth - 1, seen)?.let { return it }
                } catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
            }
            type = type.superclass
        }
        return null
    }

    /**
     * Attaches green dot directly to the contact avatar container (contact_selector).
     * Positions precisely on the bottom-right of the avatar circle with border stroke.
     */
    private fun ensureDotView(row: View): View? {
        if (!showDot) return null
        val contactSelectorId = HostResourceIds.id(row.context, "contact_selector")
        val container = if (contactSelectorId != 0) row.findViewById<ViewGroup>(contactSelectorId) else null
        val targetGroup = container ?: (row as? ViewGroup) ?: return null

        var dot = targetGroup.findViewById<View>(ID_DOT)
        if (dot == null) {
            val night = (row.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            dot = View(row.context).apply {
                id = ID_DOT
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(37, 211, 102)) // WhatsApp online green #25D366
                    // Ring matches the row surface so the dot reads as cut out of the avatar.
                    setStroke(dp(2), if (night) Color.rgb(11, 20, 26) else Color.WHITE)
                }
                visibility = View.GONE
                isClickable = false
                isFocusable = false
            }
            val size = dp(14)
            if (targetGroup is FrameLayout) {
                targetGroup.addView(
                    dot,
                    FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT)
                )
                targetGroup.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    targetGroup.findViewById<View>(ID_DOT)?.let { placeDotOnAvatarRim(targetGroup, it) }
                }
                // A host row can retain identical container bounds while its photo
                // child is measured later. Observe the child as well as the parent.
                val photoId = HostResourceIds.id(targetGroup.context, "contact_photo")
                if (photoId != 0) {
                    targetGroup.findViewById<View>(photoId)?.addOnLayoutChangeListener {
                        _, _, _, _, _, _, _, _, _ ->
                        targetGroup.findViewById<View>(ID_DOT)?.let {
                            placeDotOnAvatarRim(targetGroup, it)
                        }
                    }
                }
                placeDotOnAvatarRim(targetGroup, dot)
            } else {
                targetGroup.addView(dot, ViewGroup.LayoutParams(size, size))
            }
        }
        return dot
    }

    /**
     * Centers the dot on the avatar circle's bottom-right rim (45 degrees), measured from the real
     * photo view, so it sits on the avatar corner at any avatar size, story ring or density.
     * Coordinates are absolute (not start-relative), so absolute LEFT gravity keeps it correct in RTL.
     */
    private fun placeDotOnAvatarRim(group: ViewGroup, dot: View) {
        val photoId = HostResourceIds.id(group.context, "contact_photo")
        val photo = if (photoId != 0) group.findViewById<View>(photoId) else null
        val params = dot.layoutParams as? FrameLayout.LayoutParams ?: return
        // Keep the older, measured bottom-end placement when this host layout
        // exposes an avatar container but no separate (or measured) photo view.
        // The layout listener retries the precise rim placement after measurement.
        if (photo == null || photo.width <= 0 || photo.height <= 0) {
            val inset = dp(4)
            if (params.gravity == (Gravity.BOTTOM or Gravity.END) &&
                params.marginEnd == inset && params.bottomMargin == inset) return
            params.gravity = Gravity.BOTTOM or Gravity.END
            params.leftMargin = 0
            params.topMargin = 0
            params.marginEnd = inset
            params.bottomMargin = inset
            dot.layoutParams = params
            return
        }
        val bounds = Rect(0, 0, photo.width, photo.height)
        try {
            group.offsetDescendantRectToMyCoords(photo, bounds)
        } catch (_: IllegalArgumentException) {
            return
        }
        val radius = minOf(photo.width, photo.height) / 2f
        val size = maxOf(dp(10), (radius * 2f * 0.27f).toInt())
        val rim = radius * 0.7071f
        val left = (bounds.exactCenterX() + rim - size / 2f).toInt()
        val top = (bounds.exactCenterY() + rim - size / 2f).toInt()
        if (params.width == size && params.height == size &&
            params.leftMargin == left && params.topMargin == top &&
            params.gravity == (Gravity.TOP or Gravity.LEFT) &&
            !params.isMarginRelative()) return
        // Android re-resolves start/end margins over absolute left/right margins.
        // A prior BOTTOM|END fallback leaves relative margin flags set even after
        // marginEnd=0. Use fresh absolute params when the photo becomes measured.
        dot.layoutParams = FrameLayout.LayoutParams(
            size, size, Gravity.TOP or Gravity.LEFT
        ).apply {
            leftMargin = left
            topMargin = top
        }
    }

    private fun ensureTextView(row: View): TextView? {
        if (!showText) return null
        val group = row as? ViewGroup ?: return null
        group.findViewById<TextView>(ID_TEXT)?.let { return it }
        val text = TextView(row.context).apply {
            id = ID_TEXT
            textSize = 11.5f
            setTextColor(Color.rgb(37, 211, 102))
            maxLines = 1
            visibility = View.GONE
            isClickable = false
        }
        // The row container is a horizontal LinearLayout: appending to it would place the text past
        // the end of the message column. Insert it into the vertical content column, right under the
        // name/date header, so it takes real layout space only while visible.
        val contentId = HostResourceIds.id(row.context, "conversations_row_content")
            .takeIf { it != 0 } ?: HostResourceIds.id(row.context, "row_content")
        val content = if (contentId != 0) group.findViewById<View>(contentId) as? ViewGroup else null
        if (content is LinearLayout && content.orientation == LinearLayout.VERTICAL) {
            content.addView(
                text,
                minOf(1, content.childCount),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return text
        }
        if (group is FrameLayout) {
            group.addView(
                text,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), Gravity.END or Gravity.TOP
                ).apply {
                    marginEnd = dp(14)
                    topMargin = dp(32)
                }
            )
            return text
        }
        return null
    }

    private fun render(row: View, state: PresenceStateStore.State?) {
        val dot = row.findViewById<View>(ID_DOT)
        val text = row.findViewById<TextView>(ID_TEXT)

        val isOnline = state?.known == true && state.online
        dot?.visibility = if (isOnline) View.VISIBLE else View.GONE

        text?.let { tv ->
            when {
                state == null || !state.known -> {
                    tv.text = ""
                    tv.visibility = View.GONE
                }
                state.online -> {
                    tv.text = UiText.get(R.string.waex_ui_online, "online")
                    tv.visibility = View.VISIBLE
                }
                else -> {
                    val time = DateFormat.getTimeFormat(row.context).format(state.changedAtMillis)
                    tv.text = UiText.get(R.string.waex_ui_last_seen_1_s, "last seen %1\$s", time)
                    tv.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun findConversationRow(sources: List<Any?>): View? {
        val candidates = ArrayList<View>()
        val seen = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        sources.forEach { collectViews(it, 4, seen, candidates) }
        val sample = candidates.firstOrNull() ?: return null
        val resources = sample.resources
        val packageName = sample.context.packageName
        val contactSelectorId = HostResourceIds.id(sample.context, "contact_selector")
        val conversationContentId = HostResourceIds.id(sample.context, "conversations_row_content")
        val rowContentId = HostResourceIds.id(sample.context, "row_content")
        if (contactSelectorId == 0 || (conversationContentId == 0 && rowContentId == 0)) return null
        return candidates.asSequence()
            .filterIsInstance<ViewGroup>()
            .filter { candidate ->
                candidate.findViewById<View>(contactSelectorId) != null &&
                    ((conversationContentId != 0 && candidate.findViewById<View>(conversationContentId) != null) ||
                        (rowContentId != 0 && candidate.findViewById<View>(rowContentId) != null))
            }
            .filter { it.height == 0 || it.height in dp(56)..dp(128) }
            .minByOrNull { candidate ->
                val width = if (candidate.width > 0) candidate.width else Int.MAX_VALUE / 1024
                val height = if (candidate.height > 0) candidate.height else Int.MAX_VALUE / 1024
                width.toLong() * height.toLong()
            }
    }

    private fun collectViews(value: Any?, depth: Int, seen: MutableSet<Any>, output: MutableList<View>) {
        if (value == null || depth < 0 || !seen.add(value)) return
        if (value is View) {
            output.add(value)
            return
        }
        var type: Class<*>? = value.javaClass
        while (type != null && !type.name.startsWith("java.") && !type.name.startsWith("android.")) {
            for (field in HostFields.declared(type)) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    collectViews(field.get(value), depth - 1, seen, output)
                } catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
            }
            type = type.superclass
        }
    }

    private fun findJid(values: Array<out Any?>?, depth: Int): String? {
        if (values == null) return null
        values.forEach { jidFromTypedAccessor(it)?.let { jid -> return jid } }
        val seen = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        values.forEach { findJid(it, depth, seen)?.let { jid -> return jid } }
        return null
    }

    /**
     * The host's Jid.toString() is masked for logging (last digits only), while presence events carry
     * the full identifier. Resolve the full form without relying on any member name: probe the
     * class's public no-arg String members and keep the longest well-formed identity, so a masked
     * variant can never win over the complete one.
     */
    private fun fullJidString(jid: Any?): String? {
        if (jid == null) return null
        val type = jid.javaClass
        val methods = stringMembers.getOrPut(type) {
            try {
                type.methods.filter {
                    it.parameterTypes.size == 0 && it.returnType == String::class.java &&
                        !Modifier.isStatic(it.modifiers) && it.declaringClass != Any::class.java
                }
            } catch (_: Throwable) { emptyList() }
        }
        var best: String? = null
        for (method in methods) {
            try {
                val rendered = method.invoke(jid) as? String ?: continue
                val match = JID_REGEX.find(rendered)?.value ?: continue
                if (best == null || match.length > best.length) best = match
            } catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
        }
        return best ?: try { JID_REGEX.find(jid.toString())?.value } catch (_: Throwable) { null }
    }

    /** Full identity of a Jid instance, or of any object's string form when it is not a Jid. */
    private fun identityOf(value: Any?): String? {
        if (value == null) return null
        val jidType = hostJidType
        return if (jidType != null && jidType.isInstance(value)) fullJidString(value)
        else try { JID_REGEX.find(value.toString())?.value } catch (_: Throwable) { null }
    }

    /**
     * Every identity (PN and LID forms) the host hands this row, found by type: any value that is an
     * instance of the host Jid type, reached directly, through no-arg Jid-returning accessors, or
     * through fields of the bound objects. Presence events may use a different identity form than the
     * row's primary key, so all of them are registered as aliases of the row.
     */
    private fun collectJidIdentities(sources: List<Any?>): Set<String> {
        val out = LinkedHashSet<String>()
        val seen = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        sources.forEach { gatherJidIdentities(it, 3, seen, out) }
        return out
    }

    private fun gatherJidIdentities(value: Any?, depth: Int, seen: MutableSet<Any>, out: MutableSet<String>) {
        val jidType = hostJidType ?: return
        if (value == null || depth < 0) return
        if (value is View || value is Context || value is CharSequence || value is Number) return
        if (jidType.isInstance(value)) {
            try {
                fullJidString(value)?.let { out.add(PresenceStateStore.normalize(it)) }
            } catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
            return
        }
        if (!seen.add(value)) return
        val type = value.javaClass
        if (type.name.startsWith("java.") || type.name.startsWith("android.")) return
        val accessors = accessorCache.getOrPut(type) {
            try {
                type.methods.filter {
                    it.parameterTypes.size == 0 && !Modifier.isStatic(it.modifiers) &&
                        jidType.isAssignableFrom(it.returnType)
                }
            } catch (_: Throwable) { emptyList() }
        }
        for (accessor in accessors) {
            try { gatherJidIdentities(accessor.invoke(value), depth - 1, seen, out) }
            catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
        }
        var cursor: Class<*>? = type
        while (cursor != null && !cursor.name.startsWith("java.") && !cursor.name.startsWith("android.")) {
            for (field in HostFields.declared(cursor)) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                try {
                    field.isAccessible = true
                    gatherJidIdentities(field.get(value), depth - 1, seen, out)
                } catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
            }
            cursor = cursor.superclass
        }
    }

    /** Name-independent: any public no-arg method whose declared return type is the host Jid type. */
    private fun jidFromTypedAccessor(value: Any?): String? {
        val jidType = hostJidType ?: return null
        if (value == null) return null
        val accessors = accessorCache.getOrPut(value.javaClass) {
            try {
                value.javaClass.methods.filter {
                    it.parameterTypes.size == 0 && !Modifier.isStatic(it.modifiers) &&
                        jidType.isAssignableFrom(it.returnType)
                }
            } catch (_: Throwable) { emptyList() }
        }
        for (accessor in accessors) {
            try {
                identityOf(accessor.invoke(value))?.let { return it }
            } catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
        }
        return null
    }

    private fun findJid(value: Any?, depth: Int, seen: MutableSet<Any>): String? {
        if (value == null || depth < 0 || !seen.add(value)) return null
        identityOf(value)?.let { return it }
        var type: Class<*>? = value.javaClass
        while (type != null && !type.name.startsWith("java.") && !type.name.startsWith("android.")) {
            for (field in HostFields.declared(type)) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findJid(field.get(value), depth - 1, seen)?.let { return it }
                } catch (ignored: Throwable) { HookErrors.swallowed("OnlinePresenceIndicatorsHook", ignored) }
            }
            type = type.superclass
        }
        return null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun recordSubscription(key: String, timestamp: Long) {
        subscriptions[key] = timestamp
        if (subscriptions.size > MAX_TRACKED_IDENTITIES) {
            subscriptions.entries.minByOrNull { it.value }?.let { subscriptions.remove(it.key, it.value) }
        }
    }

    private fun recordIdentityAlias(alias: String, jid: String) {
        if (alias.isBlank() || jid.isBlank()) return
        if (identityAliases.size >= MAX_TRACKED_IDENTITIES && !identityAliases.containsKey(alias)) {
            identityAliases.clear()
        }
        identityAliases.compute(alias) { _, previous ->
            if (previous == null || previous == jid) jid else ""
        }
        queuePresenceRefresh()
    }

    companion object {
        private const val MAX_TRACKED_IDENTITIES = 2048
        private val JID_REGEX =
            Regex("[+0-9A-Za-z._:-]+@lid|[+0-9A-Za-z._:-]+@(s\\.)?whatsapp\\.net|[+0-9A-Za-z._:-]+@g\\.us")
        private const val ID_DOT = 0x7f0f7a11
        private const val ID_TEXT = 0x7f0f7a12
        private const val TAG_BOUND_JID = 0x7f0f7a13
        private val subscriptionDiagnostics = AtomicInteger()
        private val subscriptions = ConcurrentHashMap<String, Long>()
        private val identityAliases = ConcurrentHashMap<String, String>()
    }
}
