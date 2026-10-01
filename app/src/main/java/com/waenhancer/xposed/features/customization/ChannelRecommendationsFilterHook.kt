package com.waenhancer.xposed.features.customization

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher

/**
 * Channel Recommendations Filter Hook.
 * Automatically hides newsletter/channel recommendations and cleans up the updates tab.
 */
class ChannelRecommendationsFilterHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "Channel Recommendations Filter"

    override fun hook() {
        if (!isEnabled("channels_enhancements", false)) return

        try {
            val recClass = DexSearchEngine.getInstance().findClassWithCache(
                context,
                classLoader,
                "wpp_channel_rec_class"
            ) { bridge, loader ->
                val result = bridge.findClass(
                    FindClass.create().matcher(
                        ClassMatcher.create().addUsingString("hasNewsletterSubscriptions", StringMatchType.Contains)
                    )
                ).firstOrNull()
                result?.getInstance(loader)
            }

            if (recClass != null) {
                XposedBridge.hookAllConstructors(recClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        view.visibility = View.GONE
                        val layoutParams = view.layoutParams
                        if (layoutParams != null) {
                            layoutParams.height = 0
                            layoutParams.width = 0
                            view.layoutParams = layoutParams
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX][ChannelsFilter] Error hooking channel recommendation container: ${t.message}")
        }
    }
}
