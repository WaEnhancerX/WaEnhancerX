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

/**
 * Prevents messages deleted via "Delete For Me" from being wiped from local storage,
 * keeping the messages accessible in the chat.
 */
class PreserveDeleteForMeHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "PreserveDeleteForMe"

    companion object {
        private const val TAG = "[WAEX:PreserveDeleteForMe]"
        private const val PREF_KEY = "preserve_delete_for_me"
    }

    override fun hook() {
        try {
            val deleteMethod = resolveDeleteForMeMethod()
            if (deleteMethod != null) {
                XposedBridge.hookMethod(
                    deleteMethod,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isEnabled(PREF_KEY, false)) {
                                XposedBridge.log("$TAG Preserving local message from Delete For Me deletion.")
                                param.result = null
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hook installed successfully on ${deleteMethod.name}")
            } else {
                XposedBridge.log("$TAG Warning: DeleteForMe method not resolved.")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to install PreserveDeleteForMe hook: ${t.message}")
        }
    }

    private fun resolveDeleteForMeMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_delete_for_me_method",
                { bridge, loader ->
                    val data = bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "CoreMessageStore/deleteMessageForMe",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull() ?: bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "msgstore/delete-for-me",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()
                    data?.getMethodInstance(loader)
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Exception while searching DeleteForMe method: ${t.message}")
            null
        }
    }
}
