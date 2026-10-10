package com.waenhancer.xposed.features.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * Opens a prefilled chat when a user-approved automation fires while WhatsApp
 * is foregrounded, and emits notifications for incoming message events.
 * It does not silently transmit messages on the user's behalf.
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
                    // E.164 phone numbers have at most 15 digits. Bound untrusted
                    // broadcast data to avoid giant URI allocations in WhatsApp.
                    if (cleanNumber.length in 7..15 && messageText.length <= 4096) {
                        openPreparedMessage(cleanNumber, messageText)
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

    /** The original implementation only constructed a Uri and did nothing.
     * Use a WhatsApp-owned deep link with explicit user confirmation. Android
     * restricts background activity launches, so never force a background pop-up. */
    private fun openPreparedMessage(phoneNumber: String, text: String) {
        mainHandler.post {
            try {
                val foreground = ActivityTracker.getCurrentActivity()
                if (foreground == null || foreground.isFinishing || foreground.isDestroyed) {
                    XposedBridge.log("[WAEX][Tasker] Open WhatsApp before requesting a prepared message")
                    return@post
                }
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber&text=${Uri.encode(text)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(context.packageName)
                }
                foreground.startActivity(intent)
            } catch (t: Throwable) {
                XposedBridge.log("[WAEX][Tasker] Cannot open prepared message: ${t.message}")
            }
        }
    }

    companion object {
        const val ACTION_SEND_MESSAGE = "com.waenhancer.MESSAGE_SENT"
        const val ACTION_MESSAGE_RECEIVED = "com.waenhancer.MESSAGE_RECEIVED"
    }
}
