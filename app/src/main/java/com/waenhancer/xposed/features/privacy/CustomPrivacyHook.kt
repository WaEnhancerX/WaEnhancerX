package com.waenhancer.xposed.features.privacy


import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import android.R as AndroidR

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteDatabase
import java.io.File
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.components.WaexBottomSheet
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import android.content.res.Configuration
import com.waenhancer.utils.ContactNameResolver

/**
 * Native in-WhatsApp Per-Contact Privacy Hook with authentic WDS (WhatsApp Design System) UI elements.
 */
class CustomPrivacyHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String = "Custom Privacy (Per-Contact)"

    companion object {
        const val MENU_ID_CUSTOM_PRIVACY = 0x7EAE0007
        const val VIEW_TAG_CUSTOM_PRIVACY_TILE = "waex_custom_privacy_tile"
        const val TAG = "[WAEX_CUSTOM_PRIVACY]"

        @JvmStatic
        fun getContactPrivacy(prefs: SharedPreferences, jidOrNumber: String?): JSONObject? {
            if (jidOrNumber.isNullOrEmpty()) return null
            if (!prefs.getBoolean("custom_privacy", true)) return null
            val cleanNumber = jidOrNumber.substringBefore("@").substringBefore(":")
            val jsonStr = prefs.getString("${cleanNumber}_privacy", null)
                ?: prefs.getString("${jidOrNumber}_privacy", null)
                ?: prefs.getString("per_contact_rules_$jidOrNumber", null)
            if (jsonStr.isNullOrEmpty()) return null
            return try {
                JSONObject(jsonStr)
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun hook() {
        hookContactAndGroupInfo()
        hookOptionsMenu()
    }

    private fun isCustomPrivacyActive(): Boolean {
        return prefs.getBoolean("custom_privacy", true)
    }

    private fun getPlacementType(): String {
        return prefs.getString("custom_privacy_type", "1") ?: "1"
    }

    private fun hookContactAndGroupInfo() {
        val targetClassNames = listOf(
            "com.whatsapp.chatinfo.ContactInfoActivity",
            "com.whatsapp.chatinfo.view.ContactInfoActivity",
            "com.whatsapp.group.GroupChatInfoActivity",
            "com.whatsapp.group.view.GroupChatInfoActivity"
        )

        for (className in targetClassNames) {
            try {
                val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
                XposedBridge.hookAllMethods(clazz, "onStart", object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        injectInfoScreenTile(activity)
                    }
                })
                XposedBridge.hookAllMethods(clazz, "onResume", object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        injectInfoScreenTile(activity)
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to hook class $className: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun hookOptionsMenu() {
        val targetClasses = listOf(
            "com.whatsapp.Conversation",
            "com.whatsapp.conversation.ConversationListView",
            "com.whatsapp.chatinfo.ContactInfoActivity",
            "com.whatsapp.chatinfo.view.ContactInfoActivity",
            "com.whatsapp.group.GroupChatInfoActivity"
        )

        for (className in targetClasses) {
            try {
                val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
                XposedBridge.hookAllMethods(clazz, "onCreateOptionsMenu", object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val menu = param.args?.firstOrNull() as? Menu ?: return
                        val activity = param.thisObject as? Activity ?: return
                        handleMenuInjection(menu, activity)
                    }
                })
                XposedBridge.hookAllMethods(clazz, "onPrepareOptionsMenu", object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val menu = param.args?.firstOrNull() as? Menu ?: return
                        val activity = param.thisObject as? Activity ?: return
                        handleMenuInjection(menu, activity)
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to hook menu on $className: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun handleMenuInjection(menu: Menu, activity: Activity) {
        if (!isCustomPrivacyActive()) return
        val type = getPlacementType()
        // type "2" = Menu, type "3" = Both
        if (type != "2" && type != "3") return

        if (menu.findItem(MENU_ID_CUSTOM_PRIVACY) != null) return

        val menuItem = menu.add(0, MENU_ID_CUSTOM_PRIVACY, 100, UiText.get(R.string.waex_ui_custom_privacy, "Custom Privacy"))
        menuItem.setOnMenuItemClickListener {
            val jid = extractJid(activity)
            showCustomPrivacyBottomSheet(activity, jid)
            true
        }
    }

    private fun injectInfoScreenTile(activity: Activity) {
        if (!isCustomPrivacyActive()) return
        val type = getPlacementType()
        // type "1" = Info screen, type "3" = Both
        if (type != "1" && type != "3") return

        if (activity.window.decorView.findViewWithTag<View>(VIEW_TAG_CUSTOM_PRIVACY_TILE) != null) return

        try {
            val jid = extractJid(activity)
            val res = activity.resources
            val pkg = activity.packageName

            var targetContainer: ViewGroup? = null

            val containerIds = listOf(
                "contact_info_security_card_layout",
                "contact_info_card_layout",
                "chat_info_layout",
                "details_card_layout",
                "security_card"
            )

            for (resName in containerIds) {
                val id = res.getIdentifier(resName, "id", pkg)
                if (id != 0) {
                    targetContainer = activity.findViewById(id)
                    if (targetContainer != null) break
                }
            }

            if (targetContainer == null) {
                val root = activity.findViewById<ViewGroup>(AndroidR.id.content)
                targetContainer = findFirstSuitableContainer(root)
            }

            if (targetContainer != null) {
                val tile = createInfoScreenTileView(activity) {
                    showCustomPrivacyBottomSheet(activity, jid)
                }
                tile.tag = VIEW_TAG_CUSTOM_PRIVACY_TILE
                targetContainer.addView(tile, 0)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to inject tile: ${t.javaClass.simpleName}")
        }
    }

    private fun findFirstSuitableContainer(view: View?): ViewGroup? {
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is ScrollView && child.childCount > 0 && child.getChildAt(0) is ViewGroup) {
                return child.getChildAt(0) as ViewGroup
            }
            if (child is ViewGroup && child.childCount > 1) {
                val found = findFirstSuitableContainer(child)
                if (found != null) return found
            }
        }
        return if (view is LinearLayout) view else null
    }

    private fun extractJid(activity: Activity): String {
        return ContactNameResolver.extractJidFromActivity(activity)
    }

    private fun extractDisplayName(activity: Activity, jid: String, fallbackNumber: String): String {
        return ContactNameResolver.resolveFromActivity(activity, jid, fallbackNumber)
    }

    private fun createInfoScreenTileView(activity: Activity, onClick: () -> Unit): View {
        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val itemLayout = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val pY = dp(activity, 14)
                val pX = dp(activity, 16)
                setPadding(pX, pY, pX, pY)
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true

            val rippleColor = if (isDark) 0x22FFFFFF else 0x15000000
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, ColorDrawable(Color.WHITE))
            setOnClickListener { onClick() }
        }

        // Icon
        val iconView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)).apply {
                marginEnd = dp(activity, 16)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(AndroidR.drawable.ic_lock_lock)
            setColorFilter(if (isDark) 0xFF8696A0.toInt() else 0xFF667781.toInt())
        }
        itemLayout.addView(iconView)

        // Text container
        val textContainer = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = UiText.get(R.string.waex_ui_custom_privacy, "Custom Privacy")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (isDark) 0xFFE9EDEF.toInt() else 0xFF111B21.toInt())
        }
        textContainer.addView(titleView)

        val summaryView = TextView(activity).apply {
            text = UiText.get(R.string.waex_ui_enable_or_customize_per_contact_privac, "Enable or customize per-contact privacy rules")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(if (isDark) 0xFF8696A0.toInt() else 0xFF667781.toInt())
            setPadding(0, dp(activity, 2), 0, 0)
        }
        textContainer.addView(summaryView)

        itemLayout.addView(textContainer)

        return itemLayout
    }

    private fun showCustomPrivacyBottomSheet(activity: Activity, jid: String) {
        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cleanNumber = jid.substringBefore("@").substringBefore(":")
        val existingJson = getContactPrivacy(prefs, jid) ?: JSONObject()

        val rulesList = listOf(
            Triple("HideSeen", UiText.get(R.string.waex_ui_hide_blue_ticks, "Hide Blue Ticks"), UiText.get(R.string.waex_ui_don_t_send_read_receipts_for_received, "Don't send read receipts for received messages")),
            Triple("HideViewStatus", UiText.get(R.string.waex_ui_hide_status_view, "Hide Status View"), UiText.get(R.string.waex_ui_view_contact_s_status_updates_anonymou, "View contact's status updates anonymously")),
            Triple("HideReceipt", UiText.get(R.string.waex_ui_hide_delivered_receipts, "Hide Delivered Receipts"), UiText.get(R.string.waex_ui_don_t_send_second_delivery_checkmark, "Don't send second delivery checkmark")),
            Triple("HideTyping", UiText.get(R.string.waex_ui_hide_typing_indicator, "Hide Typing Indicator"), UiText.get(R.string.waex_ui_don_t_show_typing_status_when_composin, "Don't show typing status when composing")),
            Triple("HideRecording", UiText.get(R.string.waex_ui_hide_voice_recording, "Hide Voice Recording"), UiText.get(R.string.waex_ui_don_t_show_recording_audio_status, "Don't show recording audio status")),
            Triple("AntiRevoke", UiText.get(R.string.feat_anti_revoke_title, "Anti-Revoke Messages"), UiText.get(R.string.waex_ui_preserve_deleted_messages_in_this_chat, "Preserve deleted messages in this chat")),
            Triple("BlockCall", UiText.get(R.string.waex_ui_block_incoming_calls, "Block Incoming Calls"), UiText.get(R.string.waex_ui_silently_decline_calls_from_this_conta, "Silently decline calls from this contact"))
        )

        val checkStateMap = mutableMapOf<String, Boolean>()
        for (triple in rulesList) {
            val key = triple.first
            checkStateMap[key] = existingJson.optBoolean(key, false)
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padX = dp(activity, 0)
            val padY = dp(activity, 4)
            setPadding(padX, padY, padX, padY)
        }

        for (triple in rulesList) {
            val key = triple.first
            val title = triple.second
            val subtitle = triple.third
            val isInitiallyChecked = checkStateMap[key] ?: false

            val switchTile = createWdsSwitchTile(
                activity = activity,
                title = title,
                subtitle = subtitle,
                isDark = isDark,
                isChecked = isInitiallyChecked
            ) { checked ->
                checkStateMap[key] = checked
            }

            contentLayout.addView(switchTile)
        }

        // WhatsApp WDS Bottom Sheet
        val bottomSheet = WaexBottomSheet(activity)
            .setTitle(UiText.get(R.string.waex_ui_custom_privacy, "Custom Privacy"))
            .setView(contentLayout)
            .setPositiveButton(UiText.get(R.string.action_save, "Save"), DialogInterface.OnClickListener { dialog, _ ->
                try {
                    val updatedJson = JSONObject()
                    for ((k, v) in checkStateMap) {
                        updatedJson.put(k, v)
                    }
                    val displayName = extractDisplayName(activity, jid, cleanNumber)
                    updatedJson.put("name", displayName)

                    val jsonStr = updatedJson.toString()
                    prefs.edit()
                        .putString("${cleanNumber}_privacy", jsonStr)
                        .putString("${jid}_privacy", jsonStr)
                        .putString("per_contact_rules_$jid", jsonStr)
                        .apply()

                    Toast.makeText(activity, UiText.get(R.string.waex_ui_custom_privacy_saved, "Custom privacy saved"), Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG Failed to save custom privacy: ${t.javaClass.simpleName}")
                }
                dialog.dismiss()
            })
            .setNegativeButton(UiText.get(R.string.action_cancel, "Cancel"), DialogInterface.OnClickListener { dialog, _ ->
                dialog.dismiss()
            })

        bottomSheet.show()
    }

    private fun createWdsSwitchTile(
        activity: Activity,
        title: String,
        subtitle: String,
        isDark: Boolean,
        isChecked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ): View {
        val rowLayout = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true

            val padX = dp(activity, 20)
            val padY = dp(activity, 14)
            setPadding(padX, padY, padX, padY)

            val rippleColor = if (isDark) 0x22FFFFFF else 0x15000000
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, ColorDrawable(Color.WHITE))
        }

        // Text Column
        val textContainer = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 16)
            }
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (isDark) 0xFFE9EDEF.toInt() else 0xFF111B21.toInt())
        }
        textContainer.addView(titleView)

        val summaryView = TextView(activity).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (isDark) 0xFF8696A0.toInt() else 0xFF667781.toInt())
            setPadding(0, dp(activity, 3), 0, 0)
        }
        textContainer.addView(summaryView)

        rowLayout.addView(textContainer)

        // Native Switch
        val switchWidget = createWdsSwitchWidget(activity).apply {
            if (this is CompoundButton) {
                this.isChecked = isChecked
                this.setOnCheckedChangeListener { _, checked ->
                    onCheckedChange(checked)
                }
            }
        }
        rowLayout.addView(switchWidget)

        rowLayout.setOnClickListener {
            if (switchWidget is CompoundButton) {
                switchWidget.isChecked = !switchWidget.isChecked
                onCheckedChange(switchWidget.isChecked)
            }
        }

        return rowLayout
    }

    private fun createWdsSwitchWidget(context: Context): View {
        return try {
            val clazz = context.classLoader.loadClass("com.whatsapp.ui.wds.components.toggle.WDSSwitch")
            clazz.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(context, null) as View
        } catch (_: Throwable) {
            try {
                val clazz = context.classLoader.loadClass("androidx.appcompat.widget.SwitchCompat")
                val switchComp = clazz.getConstructor(Context::class.java).newInstance(context) as CompoundButton
                val greenColor = 0xFF25D366.toInt()
                val grayColor = 0xFF74787A.toInt()
                val states = arrayOf(intArrayOf(AndroidR.attr.state_checked), intArrayOf())
                val trackColors = intArrayOf(0x6625D366.toInt(), 0x3374787A.toInt())
                val thumbColors = intArrayOf(greenColor, grayColor)
                XposedHelpers.callMethod(switchComp, "setTrackTintList", ColorStateList(states, trackColors))
                XposedHelpers.callMethod(switchComp, "setThumbTintList", ColorStateList(states, thumbColors))
                switchComp
            } catch (_: Throwable) {
                Switch(context).apply {
                    val greenColor = 0xFF25D366.toInt()
                    val grayColor = 0xFF74787A.toInt()
                    val states = arrayOf(intArrayOf(AndroidR.attr.state_checked), intArrayOf())
                    val trackColors = intArrayOf(0x6625D366.toInt(), 0x3374787A.toInt())
                    val thumbColors = intArrayOf(greenColor, grayColor)
                    trackTintList = ColorStateList(states, trackColors)
                    thumbTintList = ColorStateList(states, thumbColors)
                }
            }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
