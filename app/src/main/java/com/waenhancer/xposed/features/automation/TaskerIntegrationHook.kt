package com.waenhancer.xposed.features.automation

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * Tasker & External Automation Dispatcher Hook.
 * Receives external automation requests and opens a WhatsApp chat with prefilled text,
 * requiring the user to confirm sending. Preserves incoming-message event broadcasts.
 * Never silently sends messages or starts an Activity while WhatsApp is backgrounded.
 */
class TaskerIntegrationHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val launchGate = TaskerMessagePolicy.LaunchGate(1_500L)

    override val name: String
        get() = "Tasker Integration"

    override fun hook() {
        if (!isEnabled("tasker_integration", false)) return

        registerSendMessageReceiver()
        hookIncomingMessages()
    }

    private fun registerSendMessageReceiver() {
        try {
            val filter = IntentFilter(ACTION_SEND_MESSAGE)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try {
                        if (intent.action != ACTION_SEND_MESSAGE) return
                        val prepared = TaskerMessagePolicy.prepare(
                            intent.getStringExtra("number"), intent.getStringExtra("message")
                        ) ?: return
                        openPreparedMessage(prepared)
                    } catch (t: RuntimeException) {
                        // Malformed/untrusted broadcast extras must not crash WhatsApp.
                        // Do not log telephone numbers or message contents.
                        XposedBridge.log("[WAEX][Tasker] Rejected invalid automation broadcast: ${t.javaClass.simpleName}")
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][Tasker] Failed to register send broadcast receiver: ${t.message}")
        }
    }

    private fun hookIncomingMessages() {
        try {
            val receiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "waex_receipt_incoming_msg_method"
            ) { bridge, loader ->
                val results = bridge.findMethod(
                    FindMethod.create().matcher {
                        addUsingString("ReadReceipts/sendReceiptForIncomingMessage", StringMatchType.Contains)
                    }
                )
                if (results.isNotEmpty()) results[0].getMethodInstance(loader) else null
            }

            if (receiptMethod != null) {
                XposedBridge.hookMethod(receiptMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // Hooks remain installed until the host process exits. Respect
                            // the live preference before reading any incoming identifiers.
                            if (!isEnabled("tasker_integration", false)) return
                            if (param.args.size < 4) return
                            val keyObj = param.args[3] ?: return

                            // Extract remoteJid and message id
                            val rawJid = XposedHelpers.getObjectField(keyObj, "A00") ?: return
                            val jidStr = rawJid.toString()
                            if (jidStr.contains("status")) return

                            val messageId = XposedHelpers.getObjectField(keyObj, "A01")?.toString() ?: ""

                            val broadcastIntent = Intent(ACTION_MESSAGE_RECEIVED).apply {
                                putExtra("jid", jidStr)
                                putExtra("message_id", messageId)
                            }
                            // The setting can change while host fields are being resolved.
                            // A disabled or unreadable preference must not publish an event.
                            if (!isEnabled("tasker_integration", false)) return
                            context.sendBroadcast(broadcastIntent)
                        } catch (t: Throwable) {
                            // Suppress per-message hook exceptions
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][Tasker] Error hooking incoming message receipts: ${t.message}")
        }
    }

    /**
     * Launch only from a focused, visible WhatsApp Activity. ActivityTracker can
     * hold a paused Activity, so a non-null reference alone is not sufficient.
     * Android 10+ restricts background Activity launches; never bypass the limit.
     */
    private fun openPreparedMessage(prepared: TaskerMessagePolicy.PreparedMessage) {
        mainHandler.post {
            try {
                if (!isEnabled("tasker_integration", false)) return@post
                val foreground = ActivityTracker.getCurrentActivity() ?: return@post
                if (foreground.isFinishing || foreground.isDestroyed ||
                    foreground.packageName != context.packageName ||
                    foreground.window?.decorView?.hasWindowFocus() != true
                ) return@post
                if (!launchGate.tryAcquire(SystemClock.elapsedRealtime())) return@post

                val encodedText = Uri.encode(prepared.text)
                // Restrict both link variants to the hooked WhatsApp package,
                // including WA Business and supported clones. Never launch a browser.
                val primary = Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://api.whatsapp.com/send?phone=${prepared.phone}&text=$encodedText"
                )).apply { setPackage(context.packageName) }
                try {
                    foreground.startActivity(primary)
                } catch (_: ActivityNotFoundException) {
                    // Some older/modified builds resolve only the shorter wa.me form.
                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://wa.me/${prepared.phone}?text=$encodedText"
                    )).apply { setPackage(context.packageName) }
                    foreground.startActivity(fallback)
                }
            } catch (t: RuntimeException) {
                // An unsupported host link must fail without crashing WhatsApp.
                XposedBridge.log("[WAEX][Tasker] Cannot open prepared chat: ${t.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val ACTION_SEND_MESSAGE = "com.waenhancer.MESSAGE_SENT"
        const val ACTION_MESSAGE_RECEIVED = "com.waenhancer.MESSAGE_RECEIVED"
    }
}
