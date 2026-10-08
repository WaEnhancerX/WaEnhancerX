package com.waenhancer.xposed.features.customization

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Modifier
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Renders process-local presence events on currently bound conversation rows. */
class OnlinePresenceIndicatorsHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val visibleRows = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val rowOverlays = Collections.synchronizedMap(WeakHashMap<View, FrameLayout>())
    private val showDot = isEnabled("dotonline", false)
    private val showText = isEnabled("showonlinetext", false)
    @Volatile private var subscriptionReceiver: Any? = null
    private var subscriptionMethod: Method? = null

    override val name: String get() = "Online Presence Indicators"

    override fun hook() {
        if (!showDot && !showText) return
        try {
            val bindMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, "wpp_conversation_row_bind_presence_v2"
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
                context, classLoader, "wpp_presence_subscription_sender_v3"
            ) { bridge, loader ->
                val target = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().addUsingString(
                    "app/send-presence-subscription jid=", StringMatchType.Contains
                ))).firstOrNull()
                target?.callers?.firstOrNull { it.paramCount == 4 }?.getMethodInstance(loader)
                    ?: target?.getMethodInstance(loader)
            }
            subscriptionMethod?.let { method ->
                XposedBridge.log("[WAEX][OnlinePresence] Subscription method: ${method.toGenericString()}")
                XposedBridge.hookAllConstructors(method.declaringClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        subscriptionReceiver = param.thisObject
                    }
                })
            }

            XposedBridge.hookMethod(bindMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (bindDiagnostics.get() == 0) {
                        val summary = param.args?.mapIndexed { index, value ->
                            "$index:${value?.javaClass?.name}=${try { value?.toString()?.take(180) } catch (_: Throwable) { "?" }}"
                        }?.joinToString(" | ")
                        XposedBridge.log("[WAEX][OnlinePresence] Bind signature ${bindMethod.toGenericString()} args: $summary")
                        param.args?.firstOrNull()?.let { contact ->
                            val fields = contact.javaClass.declaredFields.mapNotNull { field ->
                                try {
                                    field.isAccessible = true
                                    val value = field.get(contact)
                                    "${field.name}:${field.type.name}=${value?.toString()?.take(100)}"
                                } catch (_: Throwable) { null }
                            }.joinToString(" | ")
                            XposedBridge.log("[WAEX][OnlinePresence] Contact fields: $fields")
                        }
                    }
                    val row = findConversationRow(param.thisObject)
                    val jid = findJid(param.args, 3)
                    if ((row == null || jid == null) && bindDiagnostics.getAndIncrement() < 5) {
                        XposedBridge.log("[WAEX][OnlinePresence] Bind decode row=${row != null}, jid=${jid ?: "missing"}, args=${param.args?.size ?: 0}")
                    }
                    if (row == null || jid == null) return
                    if (jid.contains("@g.us")) return
                    if (bindDiagnostics.getAndIncrement() < 5) {
                        XposedBridge.log("[WAEX][OnlinePresence] Bound ${PresenceStateStore.normalize(jid)} to ${row.javaClass.name}")
                    }
                    val contact = param.args?.firstOrNull()
                    subscriptionMethod?.let { method ->
                        if (contact != null && method.parameterTypes.size == 4) {
                            findObjectOfType(contact, method.parameterTypes[0], 4, HashSet())?.let {
                                identityAliases[PresenceStateStore.normalize(it.toString())] = PresenceStateStore.normalize(jid)
                            }
                        }
                    }
                    requestPresence(contact)
                    row.post { bindRow(row, PresenceStateStore.normalize(jid)) }
                }
            })
            PresenceStateStore.addListener { jid, state ->
                mainHandler.post {
                    val snapshot = synchronized(visibleRows) { visibleRows.entries.toList() }
                    snapshot.forEach { (row, boundJid) ->
                        if (boundJid == jid || identityAliases[jid] == boundJid) render(row, state)
                    }
                }
            }
            XposedBridge.log("[WAEX][OnlinePresence] Hooked row binder: ${bindMethod.name}")
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][OnlinePresence] Setup failed: $t")
        }
    }

    private fun bindRow(row: View, jid: String) {
        val group = row as? ViewGroup ?: return
        val overlay = ensureOverlay(group)
        visibleRows[row] = jid
        row.setTag(TAG_BOUND_JID, jid)
        render(row, PresenceStateStore.get(jid))
    }

    private fun requestPresence(contact: Any?) {
        val method = subscriptionMethod ?: return
        val receiver = subscriptionReceiver ?: return
        if (contact == null || method.parameterTypes.size != 4) return
        val jidObject = findObjectOfType(contact, method.parameterTypes[0], 4, HashSet()) ?: return
        val key = jidObject.toString()
        val now = android.os.SystemClock.uptimeMillis()
        val previous = subscriptions[key]
        if (previous != null && now - previous < 30_000L) return
        subscriptions[key] = now
        try {
            val tokenType = method.parameterTypes[2]
            val tokenConstructor = tokenType.declaredConstructors.firstOrNull()
            val token = tokenConstructor?.let { constructor ->
                constructor.isAccessible = true
                val values = constructor.parameterTypes.map { type ->
                    when {
                        !type.isPrimitive -> null
                        type == Boolean::class.javaPrimitiveType -> false
                        type == Byte::class.javaPrimitiveType -> 0.toByte()
                        type == Short::class.javaPrimitiveType -> 0.toShort()
                        type == Int::class.javaPrimitiveType -> 0
                        type == Long::class.javaPrimitiveType -> 0L
                        type == Float::class.javaPrimitiveType -> 0f
                        type == Double::class.javaPrimitiveType -> 0.0
                        type == Char::class.javaPrimitiveType -> '\u0000'
                        else -> null
                    }
                }.toTypedArray()
                constructor.newInstance(*values)
            }
            method.isAccessible = true
            method.invoke(null, jidObject, null, token, receiver)
            if (subscriptionDiagnostics.getAndIncrement() < 5) {
                XposedBridge.log("[WAEX][OnlinePresence] Requested presence for $key")
            }
        } catch (t: Throwable) {
            if (subscriptionDiagnostics.getAndIncrement() < 5) {
                XposedBridge.log("[WAEX][OnlinePresence] Presence request failed for $key: $t")
            }
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

    private fun ensureOverlay(row: ViewGroup): FrameLayout {
        rowOverlays[row]?.let { return it }
        val overlay = FrameLayout(row.context).apply {
            id = ID_OVERLAY
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (showDot) {
            overlay.addView(View(row.context).apply {
                id = ID_DOT
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(37, 211, 102))
                    setStroke(dp(2), Color.rgb(18, 27, 31))
                }
                visibility = View.GONE
            }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.START or Gravity.TOP).apply {
                marginStart = dp(53)
                topMargin = dp(7)
            })
        }
        if (showText) {
            overlay.addView(TextView(row.context).apply {
                id = ID_TEXT
                textSize = 12f
                setTextColor(Color.rgb(37, 211, 102))
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.END or Gravity.TOP).apply {
                marginEnd = dp(12)
                topMargin = dp(28)
            })
        }
        row.overlay.add(overlay)
        val updateBounds = {
            overlay.layout(0, 0, row.width, row.height)
        }
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        row.post { updateBounds() }
        rowOverlays[row] = overlay
        return overlay
    }

    private fun render(row: View, state: PresenceStateStore.State?) {
        val overlay = rowOverlays[row] ?: return
        overlay.findViewById<View>(ID_DOT)?.visibility = if (state?.online == true) View.VISIBLE else View.GONE
        overlay.findViewById<TextView>(ID_TEXT)?.let { text ->
            when {
                state == null -> { text.text = ""; text.visibility = View.GONE }
                state.online -> { text.text = "online"; text.visibility = View.VISIBLE }
                else -> {
                    val time = DateFormat.getTimeFormat(row.context).format(state.changedAtMillis)
                    text.text = "last seen $time"
                    text.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Finds the bound conversation row rather than whichever View happens to be the first
     * field on WhatsApp's filler object (currently that first field is the home search bar). */
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

    companion object {
        private const val ID_OVERLAY = 0x7f0f7a10
        private const val ID_DOT = 0x7f0f7a11
        private const val ID_TEXT = 0x7f0f7a12
        private const val TAG_BOUND_JID = 0x7f0f7a13
        private val bindDiagnostics = AtomicInteger()
        private val subscriptionDiagnostics = AtomicInteger()
        private val subscriptions = ConcurrentHashMap<String, Long>()
        private val identityAliases = ConcurrentHashMap<String, String>()
    }
}
