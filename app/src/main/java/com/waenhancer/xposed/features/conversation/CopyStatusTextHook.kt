package com.waenhancer.xposed.features.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.waenhancer.xposed.core.BaseFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Enables long-press copy functionality for status text captions and text status updates.
 */
class CopyStatusTextHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "CopyStatusText"

    companion object {
        private const val TAG = "[WAEX:CopyStatusText]"
        private const val PREF_KEY = "copy_status_text"
        private const val TAG_HOOKED = "waex_status_text_copy_hooked"
    }

    override fun hook() {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? TextView ?: return
                        val resName = getResourceEntryName(view) ?: return

                        if (resName == "caption" || resName == "message_text" || resName == "status_text") {
                            if (view.getTag(TAG_HOOKED.hashCode()) != null) return
                            view.setTag(TAG_HOOKED.hashCode(), true)

                            view.setOnLongClickListener { textView ->
                                if (isEnabled(PREF_KEY, false)) {
                                    val text = (textView as? TextView)?.text?.toString()
                                    if (!text.isNullOrBlank()) {
                                        copyToClipboard(textView.context, text)
                                        return@setOnLongClickListener true
                                    }
                                }
                                false
                            }
                        }
                    }
                }
            )
            XposedBridge.log("$TAG Hook installed successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to install CopyStatusText hook: ${t.message}")
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Status Text", text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "Copied status text to clipboard", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to copy to clipboard: ${t.message}")
        }
    }

    private fun getResourceEntryName(view: View): String? {
        return try {
            if (view.id != View.NO_ID && view.resources != null) {
                view.resources.getResourceEntryName(view.id)
            } else null
        } catch (ignored: Throwable) {
            null
        }
    }
}
