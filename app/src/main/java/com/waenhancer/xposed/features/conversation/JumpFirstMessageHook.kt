package com.waenhancer.xposed.features.conversation

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListView
import com.waenhancer.xposed.core.BaseFeature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Injects a "Jump to First Message" action into the conversation 3-dot options menu
 * allowing users to jump directly to the top/start of any chat.
 */
class JumpFirstMessageHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "JumpToFirstMessage"

    companion object {
        private const val TAG = "[WAEX:JumpFirstMessage]"
        private const val PREF_KEY = "jump_to_first_message"
        private const val MENU_ITEM_ID = 0x57414558 // 'WAEX'
        private const val MENU_TITLE = "Jump to First Message"
    }

    override fun hook() {
        try {
            val conversationClass = try {
                classLoader.loadClass("com.whatsapp.Conversation")
            } catch (ignored: Throwable) {
                null
            }

            if (conversationClass != null) {
                hookConversationMenu(conversationClass)
            } else {
                XposedHelpers.findAndHookMethod(
                    Activity::class.java,
                    "onCreateOptionsMenu",
                    Menu::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            if (!isConversationActivity(activity)) return
                            val menu = param.args[0] as? Menu ?: return
                            injectJumpMenuItem(activity, menu)
                        }
                    }
                )
            }
            XposedBridge.log("$TAG Hook installed successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to install JumpFirstMessage hook: ${t.message}")
        }
    }

    private fun hookConversationMenu(conversationClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                conversationClass,
                "onCreateOptionsMenu",
                Menu::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val menu = param.args[0] as? Menu ?: return
                        injectJumpMenuItem(activity, menu)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Could not find onCreateOptionsMenu on Conversation: ${t.message}")
        }
    }

    private fun injectJumpMenuItem(activity: Activity, menu: Menu) {
        if (!isEnabled(PREF_KEY, false)) return

        if (menu.findItem(MENU_ITEM_ID) != null) return

        val menuItem = menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, MENU_TITLE)
        menuItem.setIcon(android.R.drawable.ic_menu_upload)
        menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menuItem.setOnMenuItemClickListener {
            performJumpToTop(activity)
            true
        }
    }

    private fun performJumpToTop(activity: Activity) {
        try {
            val decorView = activity.window.decorView as? ViewGroup ?: return
            val scrollableView = findScrollableView(decorView)
            when (scrollableView) {
                is ListView -> {
                    scrollableView.setSelection(0)
                }
                is AbsListView -> {
                    scrollableView.setSelection(0)
                }
                null -> {
                    XposedBridge.log("$TAG No scrollable view found in Conversation.")
                }
                else -> {
                    try {
                        val scrollMethod = scrollableView.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType)
                        scrollMethod.invoke(scrollableView, 0)
                    } catch (ignored: Throwable) {
                        scrollableView.scrollTo(0, 0)
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error during performJumpToTop: ${t.message}")
        }
    }

    private fun findScrollableView(viewGroup: ViewGroup): View? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is AbsListView) {
                return child
            }
            if (child.javaClass.name.contains("RecyclerView")) {
                return child
            }
            if (child is ViewGroup) {
                val found = findScrollableView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isConversationActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name.contains("Conversation") || name.contains("ChatActivity")
    }
}
