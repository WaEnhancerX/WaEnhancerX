package com.waenhancer.xposed.features.conversation

import android.app.Activity
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import com.waenhancer.xposed.core.BaseFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Minor Fixes & Stability Initializer for WhatsApp internals.
 *
 * Ensures critical internal providers such as ML Kit (com.google.mlkit.common.internal.MlKitInitProvider)
 * are properly initialized before DocumentPickerActivity or related sub-activities instantiate, preventing
 * crashes during document and media selection in WhatsApp.
 */
class MinorFixesHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "MinorFixes"

    companion object {
        private const val TAG = "[WAEX:MinorFixes]"
        private const val DOCUMENT_PICKER_ACTIVITY = "com.whatsapp.documentpicker.DocumentPickerActivity"
        private const val ML_KIT_INIT_PROVIDER = "com.google.mlkit.common.internal.MlKitInitProvider"
        private val mlKitInitLock = Any()
        @Volatile
        private var isMlKitInitialized = false
    }

    override fun hook() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.javaClass.name == DOCUMENT_PICKER_ACTIVITY) {
                            ensureMlKitInitialized(activity)
                        }
                    }
                }
            )
            XposedBridge.log("$TAG MinorFixes hook installed successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error installing MinorFixes hook: ${t.message}")
        }
    }

    private fun ensureMlKitInitialized(activity: Activity) {
        synchronized(mlKitInitLock) {
            if (isMlKitInitialized) return

            try {
                val providerClass = Class.forName(
                    ML_KIT_INIT_PROVIDER,
                    true,
                    activity.classLoader
                )
                val provider = providerClass.getDeclaredConstructor().newInstance() as ContentProvider
                val providerInfo = activity.packageManager.getProviderInfo(
                    ComponentName(activity.packageName, ML_KIT_INIT_PROVIDER),
                    PackageManager.GET_META_DATA
                )

                provider.attachInfo(activity.applicationContext, providerInfo)
                isMlKitInitialized = true
                XposedBridge.log("$TAG Successfully initialized MlKitInitProvider before DocumentPickerActivity")
            } catch (error: Throwable) {
                val alreadyInitialized = generateSequence(error) { it.cause }
                    .filterIsInstance<IllegalStateException>()
                    .any { it.message?.contains("MlKitContext is already initialized") == true }

                if (alreadyInitialized) {
                    isMlKitInitialized = true
                    return
                }

                XposedBridge.log("$TAG MlKitInitProvider initialization skipped or failed: ${error.message}")
            }
        }
    }
}
