package com.waenhancer.xposed.features.customization

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.features.automation.PresenceStateStore
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

    override val name: String get() = "Online Presence Indicators"

    override fun hook() {
        if (!showDot && !showText) return
        try {
            val bindMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, "waex_conversation_row_bind_presence_v3"
            ) { bridge, loader ->
                bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().addUsingString(
                            "ConversationViewFiller/setParentGroupProfilePhoto",
                            StringMatchType.Contains
                        )
                    )
                ).firstOrNull()?.getMethodInstance(loader)
            }
            if (bindMethod == null) {
                XposedBridge.log("[WAEX][OnlinePresence] Conversation row binder not found")
                return
            }

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

            XposedBridge.hookMethod(bindMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val row = findConversationRow(param.thisObject)
                    val jid = findJid(param.args, 3)
                    if (row == null) return
                    if (param.hasThrowable() || jid == null || jid.contains("@g.us")) {
                        queueBindRow(row, null)
                        return
                    }
                    val contact = param.args?.firstOrNull()
                    subscriptionMethod?.let { method ->
                        if (contact != null) {
                            findObjectOfType(contact, method.parameterTypes.firstOrNull() ?: Any::class.java, 4, HashSet())?.let {
                                recordIdentityAlias(
                                    PresenceStateStore.normalize(it.toString()),
                                    PresenceStateStore.normalize(jid)
                                )
                            }
                        }
                    }
                    requestPresence(contact, jid)
                    queueBindRow(row, PresenceStateStore.normalize(jid))
                }
            })
            PresenceStateStore.addListener { _, _ -> queuePresenceRefresh() }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][OnlinePresence] Setup failed: ${t.javaClass.simpleName}")
        }
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

    private fun requestPresence(contact: Any?, jid: String?) {
        val method = subscriptionMethod ?: return
        val receiver = subscriptionReceiver
        val key = jid ?: return
        val now = SystemClock.uptimeMillis()
        val previous = subscriptions[key]
        if (previous != null && now - previous < 30_000L) return
        recordSubscription(key, now)

        try {
            val params = method.parameterTypes
            val jidArg = if (params.isNotEmpty()) {
                if (params[0].isInstance(contact)) contact
                else findObjectOfType(contact, params[0], 4, HashSet())
            } else null

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

    private fun findObjectOfType(value: Any?, wanted: Class<*>, depth: Int, seen: MutableSet<Any>): Any? {
        if (value == null || depth < 0 || !seen.add(value)) return null
        if (wanted.isInstance(value)) return value
        var type: Class<*>? = value.javaClass
        while (type != null && !type.name.startsWith("java.") && !type.name.startsWith("android.")) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findObjectOfType(field.get(value), wanted, depth - 1, seen)?.let { return it }
                } catch (_: Throwable) {}
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
        val resources = row.resources
        val packageName = row.context.packageName
        val contactSelectorId = resources.getIdentifier("contact_selector", "id", packageName)
        val container = if (contactSelectorId != 0) row.findViewById<ViewGroup>(contactSelectorId) else null
        val targetGroup = container ?: (row as? ViewGroup) ?: return null

        var dot = targetGroup.findViewById<View>(ID_DOT)
        if (dot == null) {
            dot = View(row.context).apply {
                id = ID_DOT
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(37, 211, 102)) // WhatsApp online green #25D366
                    setStroke(dp(2), Color.rgb(18, 27, 31)) // Dark boundary ring
                }
                visibility = View.GONE
                isClickable = false
                isFocusable = false
            }
            val size = dp(14)
            if (targetGroup is FrameLayout) {
                val params = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                    marginEnd = dp(4)
                    bottomMargin = dp(4)
                }
                targetGroup.addView(dot, params)
            } else {
                targetGroup.addView(dot, ViewGroup.LayoutParams(size, size))
            }
        }
        return dot
    }

    private fun ensureTextView(row: View): TextView? {
        if (!showText) return null
        val group = row as? ViewGroup ?: return null
        var text = group.findViewById<TextView>(ID_TEXT)
        if (text == null) {
            text = TextView(row.context).apply {
                id = ID_TEXT
                textSize = 11.5f
                setTextColor(Color.rgb(37, 211, 102))
                visibility = View.GONE
                isClickable = false
            }
            if (group is FrameLayout) {
                val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), Gravity.END or Gravity.TOP).apply {
                    marginEnd = dp(14)
                    topMargin = dp(32)
                }
                group.addView(text, params)
            } else {
                group.addView(text)
            }
        }
        return text
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

    private fun findConversationRow(value: Any?): View? {
        val candidates = ArrayList<View>()
        collectViews(value, 4, Collections.newSetFromMap(WeakHashMap()), candidates)
        val sample = candidates.firstOrNull() ?: return null
        val resources = sample.resources
        val packageName = sample.context.packageName
        val contactSelectorId = resources.getIdentifier("contact_selector", "id", packageName)
        val conversationContentId = resources.getIdentifier("conversations_row_content", "id", packageName)
        val rowContentId = resources.getIdentifier("row_content", "id", packageName)
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
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    collectViews(field.get(value), depth - 1, seen, output)
                } catch (_: Throwable) {}
            }
            type = type.superclass
        }
    }

    private fun findJid(values: Array<out Any?>?, depth: Int): String? {
        if (values == null) return null
        val seen = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        values.forEach { findJid(it, depth, seen)?.let { jid -> return jid } }
        return null
    }

    private fun findJid(value: Any?, depth: Int, seen: MutableSet<Any>): String? {
        if (value == null || depth < 0 || !seen.add(value)) return null
        val rendered = try { value.toString() } catch (_: Throwable) { "" }
        Regex("[+0-9A-Za-z._:-]+@lid|[+0-9A-Za-z._:-]+@(s\\.)?whatsapp\\.net|[+0-9A-Za-z._:-]+@g\\.us")
            .find(rendered)?.value?.let { return it }
        var type: Class<*>? = value.javaClass
        while (type != null && !type.name.startsWith("java.") && !type.name.startsWith("android.")) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findJid(field.get(value), depth - 1, seen)?.let { return it }
                } catch (_: Throwable) {}
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
        private const val ID_DOT = 0x7f0f7a11
        private const val ID_TEXT = 0x7f0f7a12
        private const val TAG_BOUND_JID = 0x7f0f7a13
        private val bindDiagnostics = AtomicInteger()
        private val subscriptionDiagnostics = AtomicInteger()
        private val subscriptions = ConcurrentHashMap<String, Long>()
        private val identityAliases = ConcurrentHashMap<String, String>()
    }
}
