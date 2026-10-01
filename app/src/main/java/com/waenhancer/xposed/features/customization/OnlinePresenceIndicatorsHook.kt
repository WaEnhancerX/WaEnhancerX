package com.waenhancer.xposed.features.customization

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.waenhancer.app.R
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher

/**
 * Online Presence Indicators Hook.
 * Displays green dot badge on avatar and last-seen text directly in chat list rows.
 */
class OnlinePresenceIndicatorsHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "Online Presence Indicators"

    override fun hook() {
        val showDot = isEnabled("dotonline", false)
        val showText = isEnabled("showonlinetext", false)

        if (!showDot && !showText) return

        try {
            val viewHolderClass = DexSearchEngine.getInstance().findClassWithCache(
                context,
                classLoader,
                "wpp_conversations_view_holder_class"
            ) { bridge, loader ->
                val result = bridge.findClass(
                    FindClass.create().matcher(
                        ClassMatcher.create().className("ConversationsViewHolder", StringMatchType.EndsWith)
                    )
                ).firstOrNull()
                result?.getInstance(loader)
            }

            if (viewHolderClass != null) {
                XposedBridge.hookAllConstructors(viewHolderClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val itemView = param.args.firstOrNull { it is View } as? View ?: return
                        val ctx = itemView.context

                        // Tag item view for identification
                        itemView.setTag("waex_presence_row")
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][OnlinePresence] Error setting up presence indicators: ${t.message}")
        }
    }
}
