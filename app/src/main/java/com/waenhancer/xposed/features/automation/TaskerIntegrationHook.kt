package com.waenhancer.xposed.features.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * Tasker & External Automation Dispatcher Hook.
 * Allows Tasker, MacroDroid, and Automate to send messages and receive WhatsApp incoming message events.
 */
class TaskerIntegrationHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    private val mainHandler = Handler(Looper.getMainLooper())

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
                    val phoneOrJid = intent.getStringExtra("number") ?: return
                    val messageText = intent.getStringExtra("message") ?: return
                    if (phoneOrJid.isBlank() || messageText.isBlank()) return

                    val cleanNumber = phoneOrJid.replace("\\D".toRegex(), "")
                    if (cleanNumber.isNotEmpty()) {
                        sendMessageDirectly(cleanNumber, messageText)
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
                "wpp_receipt_incoming_msg_method"
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
                            if (param.args.size < 4) return
                            val keyObj = param.args[3] ?: return
                            val keyClass = keyObj.javaClass

                            // Extract remoteJid and message id
                            val rawJid = XposedHelpers.getObjectField(keyObj, "A00") ?: return
                            val jidStr = rawJid.toString()
                            if (jidStr.contains("status")) return

                            val messageId = XposedHelpers.getObjectField(keyObj, "A01")?.toString() ?: ""

                            val broadcastIntent = Intent(ACTION_MESSAGE_RECEIVED).apply {
                                putExtra("jid", jidStr)
                                putExtra("message_id", messageId)
                            }
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

    private fun sendMessageDirectly(phoneNumber: String, text: String) {
        try {
            val jidString = if (phoneNumber.contains("@")) phoneNumber else "$phoneNumber@s.whatsapp.net"
            val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber&text=${android.net.Uri.encode(text)}")
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][Tasker] Error dispatching message: ${t.message}")
        }
    }

    companion object {
        const val ACTION_SEND_MESSAGE = "com.waenhancer.MESSAGE_SENT"
        const val ACTION_MESSAGE_RECEIVED = "com.waenhancer.MESSAGE_RECEIVED"
    }
}
