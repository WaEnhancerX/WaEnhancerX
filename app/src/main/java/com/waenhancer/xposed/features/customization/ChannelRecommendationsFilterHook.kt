package com.waenhancer.xposed.features.customization

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.utils.ViewVisibilityGuard
import com.waenhancer.xposed.utils.ViewClassSelector
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

        val recClass = DexSearchEngine.getInstance().findClassWithCache(
            context,
            classLoader,
            "wpp_channel_rec_view_class_v2"
        ) { bridge, loader ->
            val candidates = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().addUsingString("hasNewsletterSubscriptions", StringMatchType.Contains)
                )
            ).map { it.getInstance(loader) }
            ViewClassSelector.uniqueView(candidates)
        }

        check(recClass != null && View::class.java.isAssignableFrom(recClass)) {
            "Channel recommendation view selector is unavailable for this target"
        }
        XposedBridge.hookAllConstructors(recClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                ViewVisibilityGuard.hideWhileEnabled(view, prefs, "channels_enhancements")
            }
        })
    }
}
