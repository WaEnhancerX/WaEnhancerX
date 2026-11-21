package com.waenhancer.xposed.features.updates

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.waenhancer.config.PreferenceStores
import com.waenhancer.update.ChangelogParser
import com.waenhancer.update.ChangelogSection
import com.waenhancer.update.ReleaseRepository
import com.waenhancer.xposed.core.components.WaexBottomSheet
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean
import android.content.res.Configuration

/**
 * Native in-WhatsApp Update BottomSheet using WhatsApp Design System (WDS) components.
 * Matches WhatsApp's native monochromatic, clean aesthetic with subtle dividers,
 * typography hierarchy, and expandable sections.
 */
object WhatsAppUpdateSheet {
    private const val TAG = "[WAEX][UpdateSheet]"
    private var sPrefs: SharedPreferences? = null
    private val shownThisSession = AtomicBoolean(false)

    @JvmStatic
    fun init(prefs: SharedPreferences) {
        sPrefs = prefs
    }

    @JvmStatic
    fun onTargetActivityResumed(activity: Activity) {
        if (shownThisSession.get()) return
        if (activity.isFinishing || activity.isDestroyed) return
        val actName = activity.javaClass.name
        if (actName.contains("Permission") || actName.contains("Stub") || actName.contains("Splash")) return

        val context: Context = activity.applicationContext
        val publicPrefs: SharedPreferences = sPrefs ?: PreferenceStores.publicStore(context)
        val availableVersion = publicPrefs.getString("available_update_version", null) ?: return
        val installedVersion = publicPrefs.getString("wae_version_name", null)
        if (installedVersion != null && ReleaseRepository.versionCode(availableVersion) <=
            ReleaseRepository.versionCode(installedVersion)) {
            clearStaleUpdate(publicPrefs)
            return
        }
        val availableTag = publicPrefs.getString("available_update_tag", availableVersion) ?: availableVersion
        val availableName = publicPrefs.getString("available_update_name", "WaEnhancerX $availableVersion") ?: "WaEnhancerX $availableVersion"
        val availableBody = publicPrefs.getString("available_update_changelog", "") ?: ""

        val now = System.currentTimeMillis()
        if (shouldSuppressIgnored(publicPrefs, availableVersion, now)) return

        if (!shownThisSession.compareAndSet(false, true)) return

        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    shownThisSession.set(false)
                    return@runOnUiThread
                }

                val customChangelogView = buildNativeChangelogView(activity, availableBody)

                WaexBottomSheet(activity)
                    .setTitle(UiText.get(R.string.waex_ui_1_s_available, "%1\$s Available", availableName))
                    .setView(customChangelogView)
                    .setPositiveButton(UiText.get(R.string.waex_ui_update_now, "Update Now")) { dialog, _ ->
                        try {
                            val intent = Intent().apply {
                                setClassName("com.waenhancer", "com.waenhancer.app.MainActivity")
                                putExtra("release_tag", availableTag)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            activity.startActivity(intent)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Failed to launch WaEnhancer: ${t.message}")
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(UiText.get(R.string.waex_ui_ignore, "Ignore")) { dialog, _ ->
                        publicPrefs.edit()
                            .putString("ignored_version", availableVersion)
                            .putLong("ignored_timestamp", System.currentTimeMillis())
                            .apply()
                        dialog.dismiss()
                    }
                    .show()
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to show WhatsApp native update bottom sheet: ${t.message}")
                shownThisSession.set(false)
            }
        }
    }

    private fun clearStaleUpdate(prefs: SharedPreferences) {
        prefs.edit()
            .remove("available_update_version")
            .remove("available_update_tag")
            .remove("available_update_name")
            .remove("available_update_changelog")
            .apply()
    }

    private fun shouldSuppressIgnored(prefs: SharedPreferences, version: String, now: Long): Boolean {
        if (prefs.getString("ignored_version", "").orEmpty() != version) return false
        val ignoredAt = prefs.getLong("ignored_timestamp", 0L)
        return when (prefs.getString("update_ignored_behavior", "next_release")) {
            "next_check" -> false
            "after_day" -> now - ignoredAt < 24L * 60L * 60L * 1000L
            "after_week" -> now - ignoredAt < 7L * 24L * 60L * 60L * 1000L
            else -> true
        }
    }

    private fun buildNativeChangelogView(context: Context, rawBody: String): View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val isDark = isDarkTheme(context)

        val textPrimary = if (isDark) 0xFFE9EDEF.toInt() else 0xFF111B21.toInt()
        val textSecondary = if (isDark) 0xFF8696A0.toInt() else 0xFF667781.toInt()
        val dividerColor = if (isDark) 0xFF202C33.toInt() else 0xFFE9EDEF.toInt()
        val accentColor = if (isDark) 0xFF21C063.toInt() else 0xFF008069.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val sections = ChangelogParser.parse(rawBody)
        if (sections.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = UiText.get(R.string.waex_ui_general_stability_improvements_and_bug, "General stability improvements and bug fixes.")
                textSize = 14f
                setTextColor(textSecondary)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            root.addView(emptyTv)
            return root
        }

        for ((index, section) in sections.withIndex()) {
            if (index > 0) {
                // Native subtle horizontal divider between sections
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    }
                    setBackgroundColor(dividerColor)
                }
                root.addView(divider)
            }

            val sectionView = createSectionView(
                context = context,
                section = section,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                accentColor = accentColor,
                dp = dp
            )
            root.addView(sectionView)
        }

        return root
    }

    private fun createSectionView(
        context: Context,
        section: ChangelogSection,
        textPrimary: Int,
        textSecondary: Int,
        accentColor: Int,
        dp: (Int) -> Int
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Section Header Row (Clickable, Native WDS style)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(0x1F8696A0),
                null,
                null
            )
        }

        // Section Title: Bold uppercase subtitle matching WhatsApp settings category style
        val titleTv = TextView(context).apply {
            text = section.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        headerRow.addView(titleTv)

        // Subtitle badge/count
        val countTv = TextView(context).apply {
            val countText = "(${section.items.size})"
            text = countText
            textSize = 13f
            setTextColor(textSecondary)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginStart = dp(6)
            }
            layoutParams = lp
        }
        headerRow.addView(countTv)

        // Minimalist chevron icon
        val chevron = TextView(context).apply {
            text = "▾"
            textSize = 16f
            setTextColor(textSecondary)
        }
        headerRow.addView(chevron)

        // Items container
        val itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), dp(10))
            visibility = View.VISIBLE
        }

        for (item in section.items) {
            val itemRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                }
                layoutParams = lp
            }

            // Bullet dot in muted secondary / WhatsApp accent
            val dot = View(context).apply {
                val size = dp(4)
                val dotLp = LinearLayout.LayoutParams(size, size).apply {
                    topMargin = dp(7)
                    marginEnd = dp(10)
                }
                layoutParams = dotLp
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(textSecondary)
                }
            }
            itemRow.addView(dot)

            val itemTv = TextView(context).apply {
                text = item
                textSize = 13.5f
                setLineSpacing(0f, 1.25f)
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            itemRow.addView(itemTv)

            itemsContainer.addView(itemRow)
        }

        // Toggle expand/collapse on header click
        var isExpanded = true
        headerRow.setOnClickListener {
            isExpanded = !isExpanded
            itemsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            chevron.text = if (isExpanded) "▾" else "▸"
        }

        container.addView(headerRow)
        container.addView(itemsContainer)
        return container
    }

    private fun isDarkTheme(context: Context): Boolean {
        return try {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mode == Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            true
        }
    }
}
