package com.waenhancer.xposed.features.privacy

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexCacheManager
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * Strips the 'Forwarded' tag and forward score indicators when forwarding messages.
 */
class HideForwardedTagHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String = "Hide Forwarded Tag"

    override fun hook() {
        hookForwardScoreSetter()
        hookDatabaseInsertion()
    }

    private fun isFeatureEnabled(): Boolean {
        return prefs.getBoolean("hide_forwarded_tag", false) || prefs.getBoolean("hidetag", false)
    }

    private fun hookForwardScoreSetter() {
        try {
            val engine = DexSearchEngine.getInstance()
            val dexCache = DexCacheManager.getInstance(context)

            // Resolve FMessage class
            val fMessageClass = dexCache.getClass(classLoader, "FMessage_Core_Class") {
                val bridge = engine.bridge ?: return@getClass null
                val match = bridge.findClass(
                    FindClass.create().matcher(
                        ClassMatcher.create().addUsingString("FMessage/getSenderUserJid/key.id", StringMatchType.Contains)
                    )
                )
                if (match.isNotEmpty()) match[0].getInstance(classLoader) else null
            } ?: return

            // Resolve Forward Classes
            val forwardClassNames = listOf(
                "UserActions/userActionForwardMessage",
                "UserActionsMessageForwarding/userActionForwardMessage"
            )
            val forwardClasses = mutableListOf<Class<*>>()
            for (str in forwardClassNames) {
                try {
                    val fc = dexCache.getClass(classLoader, "Forward_Class_$str") {
                        val bridge = engine.bridge ?: return@getClass null
                        val match = bridge.findClass(
                            FindClass.create().matcher(
                                ClassMatcher.create().addUsingString(str, StringMatchType.Contains)
                            )
                        )
                        if (match.isNotEmpty()) match[0].getInstance(classLoader) else null
                    }
                    if (fc != null) forwardClasses.add(fc)
                } catch (ignored: Throwable) {}
            }

            // Resolve setForwardScore method
            val forwardMethod: Method? = dexCache.getMethod(classLoader, "FMessage_setForwardScore") {
                val bridge = engine.bridge ?: return@getMethod null
                val methodList = bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().addUsingString("chatInfo/incrementUnseenImportantMessageCount", StringMatchType.Contains)
                    )
                )
                if (methodList.isEmpty()) return@getMethod null
                val invokes = methodList[0].invokes
                for (invoke in invokes) {
                    try {
                        val method = invoke.getMethodInstance(classLoader)
                        if (method.parameterCount == 1 &&
                            (method.parameterTypes[0] == Int::class.javaPrimitiveType || method.parameterTypes[0] == Long::class.javaPrimitiveType) &&
                            method.declaringClass == fMessageClass &&
                            method.returnType == Void.TYPE
                        ) {
                            return@getMethod method
                        }
                    } catch (ignored: Throwable) {}
                }
                null
            }

            if (forwardMethod != null) {
                XposedBridge.hookMethod(forwardMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isFeatureEnabled()) return
                        val arg = param.args[0]
                        if (arg is Number && arg.toLong() > 0) {
                            if (forwardClasses.isEmpty() || isCalledFromAnyClass(forwardClasses)) {
                                if (arg is Int) {
                                    param.args[0] = 0
                                } else if (arg is Long) {
                                    param.args[0] = 0L
                                }
                            }
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX] Error hooking forward score setter: ${t.message}")
        }
    }

    private fun hookDatabaseInsertion() {
        try {
            val insertHooks = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isFeatureEnabled()) return
                    val table = param.args[0] as? String ?: return
                    if (table == "message_forwarded") {
                        val values = param.args.getOrNull(2) as? ContentValues
                        if (values != null) {
                            values.put("forward_score", 0)
                            values.put("forward_origin", 0)
                        }
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType,
                insertHooks
            )

            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "insert",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                insertHooks
            )

            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "insertOrThrow",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                insertHooks
            )
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX] Error hooking SQLite message_forwarded: ${t.message}")
        }
    }

    private fun isCalledFromAnyClass(classes: List<Class<*>>): Boolean {
        if (classes.isEmpty()) return true
        val targetNames = classes.map { it.name }.toSet()
        val trace = Thread.currentThread().stackTrace
        for (i in 2 until trace.size) {
            if (targetNames.contains(trace[i].className)) {
                return true
            }
        }
        return false
    }
}
