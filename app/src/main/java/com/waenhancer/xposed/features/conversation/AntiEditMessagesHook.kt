package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Intercepts incoming WhatsApp message edits.
 * Preserves the original message contents and records the edit history.
 */
class AntiEditMessagesHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "AntiEditMessages"

    companion object {
        private const val TAG = "[WAEX:AntiEdit]"
        private const val PREF_KEY = "anti_edit_messages"
        private val originalMessageMap = ConcurrentHashMap<String, String>()
    }

    override fun hook() {
        try {
            val editMethod = resolveMessageEditMethod()
            if (editMethod != null) {
                XposedBridge.hookMethod(
                    editMethod,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled(PREF_KEY, false)) return

                            try {
                                val messageObj = param.args.getOrNull(0) ?: return
                                val messageKey = extractMessageKey(messageObj) ?: return
                                val currentText = extractMessageText(messageObj)

                                if (!currentText.isNullOrEmpty() && !originalMessageMap.containsKey(messageKey)) {
                                    originalMessageMap[messageKey] = currentText
                                    XposedBridge.log("$TAG Preserved original message for key: $messageKey")
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG Error preserving edit message: ${t.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hook installed successfully on ${editMethod.name}")
            } else {
                XposedBridge.log("$TAG Warning: MessageEditMethod could not be resolved via DexSearchEngine.")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to install AntiEditMessages hook: ${t.message}")
        }
    }

    private fun resolveMessageEditMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_message_edit_insert",
                { bridge, loader ->
                    val data = bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "MessageEditInfoStore/insertEditInfo",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()
                    data?.getMethodInstance(loader)
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Exception while searching message edit method: ${t.message}")
            null
        }
    }

    private fun extractMessageKey(messageObj: Any): String? {
        return try {
            val keyField = messageObj.javaClass.fields.firstOrNull { it.name == "A1J" || it.name == "key" }
                ?: messageObj.javaClass.declaredFields.firstOrNull { it.type.name.contains("Key") }
            keyField?.isAccessible = true
            val keyObj = keyField?.get(messageObj) ?: return null
            val idField = keyObj.javaClass.fields.firstOrNull { it.name == "A01" || it.name == "id" }
                ?: keyObj.javaClass.declaredFields.firstOrNull { it.type == String::class.java }
            idField?.isAccessible = true
            idField?.get(keyObj) as? String
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun extractMessageText(messageObj: Any): String? {
        return try {
            val textMethod = messageObj.javaClass.methods.firstOrNull {
                it.name == "A0w" || it.name == "getMessageText" || (it.parameterCount == 0 && it.returnType == String::class.java)
            }
            textMethod?.isAccessible = true
            textMethod?.invoke(messageObj) as? String
        } catch (ignored: Throwable) {
            null
        }
    }
}
