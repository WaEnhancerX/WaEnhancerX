package com.waenhancer.xposed.core.components

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Modern Native WhatsApp Dialog & WDS (WhatsApp Design System) component wrapper.
 * Dynamically resolves and uses WhatsApp's native Material & WDS dialog builders
 * to ensure all in-app prompts and dialogs look 100% native to WhatsApp.
 */
class NativeWhatsAppDialog(private val context: Context) {

    private var nativeBuilderInstance: Any? = null
    private var fallbackSystemBuilder: AlertDialog.Builder? = null
    private var createdDialog: Dialog? = null

    init {
        if (sBuilderMethod != null) {
            try {
                nativeBuilderInstance = sBuilderMethod!!.invoke(null, context)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to instantiate native dialog builder: ${t.message}")
                fallbackSystemBuilder = AlertDialog.Builder(context)
            }
        } else {
            fallbackSystemBuilder = AlertDialog.Builder(context)
        }
    }

    fun setTitle(title: CharSequence?): NativeWhatsAppDialog {
        if (nativeBuilderInstance != null) {
            try {
                XposedHelpers.callMethod(nativeBuilderInstance, "setTitle", title)
                return this
            } catch (t: Throwable) {
                XposedBridge.log("$TAG setTitle error: ${t.message}")
            }
        }
        fallbackSystemBuilder?.setTitle(title)
        return this
    }

    fun setMessage(message: CharSequence?): NativeWhatsAppDialog {
        if (nativeBuilderInstance != null && sSetMessageMethod != null) {
            try {
                sSetMessageMethod!!.invoke(nativeBuilderInstance, message)
                return this
            } catch (t: Throwable) {
                XposedBridge.log("$TAG setMessage error: ${t.message}")
            }
        }
        fallbackSystemBuilder?.setMessage(message)
        return this
    }

    fun setView(view: View?): NativeWhatsAppDialog {
        if (nativeBuilderInstance != null) {
            try {
                XposedHelpers.callMethod(nativeBuilderInstance, "setView", view)
                return this
            } catch (t: Throwable) {
                XposedBridge.log("$TAG setView error: ${t.message}")
            }
        }
        fallbackSystemBuilder?.setView(view)
        return this
    }

    fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): NativeWhatsAppDialog {
        if (nativeBuilderInstance != null && sPositiveButtonMethod != null) {
            try {
                sPositiveButtonMethod!!.invoke(nativeBuilderInstance, listener, text)
                return this
            } catch (t: Throwable) {
                XposedBridge.log("$TAG setPositiveButton error: ${t.message}")
            }
        }
        fallbackSystemBuilder?.setPositiveButton(text, listener)
        return this
    }

    fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): NativeWhatsAppDialog {
        if (nativeBuilderInstance != null && sNegativeButtonMethod != null) {
            try {
                sNegativeButtonMethod!!.invoke(nativeBuilderInstance, listener, text)
                return this
            } catch (t: Throwable) {
                XposedBridge.log("$TAG setNegativeButton error: ${t.message}")
            }
        }
        fallbackSystemBuilder?.setNegativeButton(text, listener)
        return this
    }

    fun create(): Dialog {
        if (createdDialog != null) return createdDialog!!

        createdDialog = if (nativeBuilderInstance != null) {
            try {
                XposedHelpers.callMethod(nativeBuilderInstance, "create") as? Dialog
                    ?: fallbackSystemBuilder?.create()
                    ?: AlertDialog.Builder(context).create()
            } catch (t: Throwable) {
                fallbackSystemBuilder?.create() ?: AlertDialog.Builder(context).create()
            }
        } else {
            fallbackSystemBuilder?.create() ?: AlertDialog.Builder(context).create()
        }
        return createdDialog!!
    }

    fun show(): Dialog? {
        if (context is Activity) {
            if (context.isFinishing || context.isDestroyed) {
                return null
            }
        }
        val dialog = create()
        try {
            dialog.show()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error showing dialog: ${t.message}")
        }
        return dialog
    }

    fun dismiss() {
        createdDialog?.dismiss()
    }

    companion object {
        private const val TAG = "[WAEX:NativeDialog]"

        private var sBuilderMethod: Method? = null
        private var sSetMessageMethod: Method? = null
        private var sPositiveButtonMethod: Method? = null
        private var sNegativeButtonMethod: Method? = null
        private var sInitialized = false

        /**
         * Initializes reflection & DexKit cache for WhatsApp's native WDS/Material Dialog builders.
         */
        @Synchronized
        fun initialize(context: Context, classLoader: ClassLoader) {
            if (sInitialized) return
            try {
                sBuilderMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_material_dialog_builder",
                    { bridge, loader ->
                        // Strategy 1: Resolve from CallConfirmationFragment Bundle method invokes
                        try {
                            val fragmentClass = loader.loadClass("com.whatsapp.calling.fragment.CallConfirmationFragment")
                            val bundleMethod = fragmentClass.declaredMethods.firstOrNull { m ->
                                m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java
                            }
                            if (bundleMethod != null) {
                                val methodData = bridge.getMethodData(bundleMethod)
                                val invokes = methodData?.invokes
                                if (invokes != null) {
                                    for (inv in invokes) {
                                        if (inv.isMethod && inv.paramCount == 1 &&
                                            inv.paramTypes[0].name == Context::class.java.name
                                        ) {
                                            return@findMethodWithCache inv.getMethodInstance(loader)
                                        }
                                    }
                                }
                            }
                        } catch (ignored: Throwable) {}

                        // Strategy 2: DexKit search for WDS/Material Dialog creation
                        val data = bridge.findMethod(
                            FindMethod.create().matcher(
                                MethodMatcher.create().addUsingString("CallConfirmationFragment", StringMatchType.Contains)
                            )
                        ).firstOrNull()
                        data?.getMethodInstance(loader)
                    }
                )

                if (sBuilderMethod != null) {
                    val dialogClass = sBuilderMethod!!.returnType
                    sSetMessageMethod = dialogClass.declaredMethods.firstOrNull { m ->
                        m.parameterCount == 1 && m.parameterTypes[0] == CharSequence::class.java
                    }
                    val buttonMethods = dialogClass.declaredMethods.filter { m ->
                        m.parameterCount == 2 &&
                                m.parameterTypes[0] == DialogInterface.OnClickListener::class.java &&
                                m.parameterTypes[1] == CharSequence::class.java
                    }
                    if (buttonMethods.isNotEmpty()) {
                        sNegativeButtonMethod = buttonMethods.firstOrNull()
                        sPositiveButtonMethod = if (buttonMethods.size > 2) buttonMethods[2] else buttonMethods.lastOrNull()
                    }
                    XposedBridge.log("$TAG Native WhatsApp Material/WDS Dialog successfully resolved: ${dialogClass.name}")
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Could not initialize native dialog: ${t.message}")
            } finally {
                sInitialized = true
            }
        }
    }
}
