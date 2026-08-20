package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.components.AlertDialogWpp
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Intercepts sticker taps in the sticker picker tray and displays
 * a native WhatsApp bottom sheet confirmation before sending the sticker.
 */
class StickerConfirmHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "StickerConfirmation"

    companion object {
        private const val TAG = "[WAEX:StickerConfirm]"
        private const val PREF_KEY = "sticker_confirm_alert"
        private const val TAG_HOOKED = "waex_sticker_hooked"
    }

    override fun hook() {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val resName = getResourceEntryName(view)
                        if (resName != "stickerContainer" && resName != "sticker_item") return

                        if (view.getTag(TAG_HOOKED.hashCode()) != null) return
                        view.setTag(TAG_HOOKED.hashCode(), true)

                        val originalClickListener = getOriginalClickListener(view)
                        view.setOnClickListener { clickedView ->
                            if (isEnabled(PREF_KEY, false)) {
                                promptSendSticker(clickedView, originalClickListener)
                            } else {
                                originalClickListener?.onClick(clickedView)
                            }
                        }
                    }
                }
            )
            XposedBridge.log("$TAG Hook installed successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook sticker attachment: ${t.message}")
        }
    }

    private fun promptSendSticker(view: View, onConfirmListener: View.OnClickListener?) {
        val currentActivity = ActivityTracker.getCurrentActivity() ?: view.context
        val stickerImageView = findStickerImageView(view)
        val drawable = stickerImageView?.drawable

        val bottomSheet = AlertDialogWpp(currentActivity).asBottomSheet()
        bottomSheet.setTitle("Send Sticker?")
        bottomSheet.setMessage("Do you want to send this sticker to the chat?")

        if (drawable != null) {
            val previewContainer = LinearLayout(currentActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 16, 0, 8)
            }
            val previewImage = ImageView(currentActivity).apply {
                setImageDrawable(drawable.constantState?.newDrawable() ?: drawable)
                val size = (110 * currentActivity.resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            previewContainer.addView(previewImage)
            bottomSheet.setView(previewContainer)
        }

        bottomSheet.setPositiveButton("Send") { _, _ ->
            onConfirmListener?.onClick(view)
        }
        bottomSheet.setNegativeButton("Cancel", null)

        bottomSheet.show()
    }

    private fun findStickerImageView(parent: View): ImageView? {
        if (parent is ImageView) return parent
        if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is ImageView) return child
            }
        }
        return null
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

    private fun getOriginalClickListener(view: View): View.OnClickListener? {
        return try {
            val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo")
            listenerInfoField.isAccessible = true
            val listenerInfo = listenerInfoField.get(view) ?: return null
            val clickListenerField = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
            clickListenerField.isAccessible = true
            clickListenerField.get(listenerInfo) as? View.OnClickListener
        } catch (ignored: Throwable) {
            null
        }
    }
}
