package com.waenhancer.xposed.features.customization

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.waenhancer.licensing.features.FilterItemsProFeature
import com.waenhancer.model.FilterItem
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.ViewHookDispatcher
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections

/**
 * Filter Items By ID Hook:
 * Enables removing or customizing WhatsApp UI elements matching specific resource IDs in real time.
 * Supports behaviors: Gone (Remove), Change Color, Opacity, and Resize.
 */
class FilterItemsHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String = "FilterItems"

    @Volatile private var targetMap: Map<Int, FilterItem> = emptyMap()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadQueued = AtomicBoolean(false)
    private val reloadTask = Runnable {
        reloadQueued.set(false)
        reloadFilters()
    }

    private fun scheduleReload() {
        if (reloadQueued.compareAndSet(false, true) && !mainHandler.post(reloadTask)) {
            reloadQueued.set(false)
        }
    }
    private val visibilityMask = 0x0000000C

    override fun hook() {
        val filterItemsRaw = prefs.getString("filter_items", null) ?: ""
        if (filterItemsRaw.isBlank() || filterItemsRaw.trim() == "[]" || !isEnabled("custom_filters", true)) {
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val item = targetMap[view.id]
                        if (item != null && FilterItem.BEHAVIOR_GONE == item.behavior) {
                            param.args[0] = View.GONE
                        }
                    }
                }
            )

            ViewHookDispatcher.onSetFlags(this) { view, flags, mask ->
                if ((mask and visibilityMask) == 0) flags
                else if (targetMap[view.id]?.behavior == FilterItem.BEHAVIOR_GONE)
                    (flags and visibilityMask.inv()) or View.GONE
                else flags
            }

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val item = targetMap[view.id]
                        if (item != null && FilterItem.BEHAVIOR_GONE == item.behavior) {
                            XposedHelpers.callMethod(view, "setMeasuredDimension", 0, 0)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val id = view.id
                        if (id > 0) {
                            val item = targetMap[id]
                            if (item != null) {
                                applyFilterToView(view, item)
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                LayoutInflater::class.java,
                "inflate",
                Int::class.javaPrimitiveType,
                ViewGroup::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val resultView = param.result as? View ?: return
                        applyFiltersRecursively(resultView)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX] Failed to hook View methods for item filtering: " + t.message)
        }

        // Initial filter load
        reloadFilters()

        // Listen for real-time preference updates from WAEX without restart
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "filter_items" || key == "custom_filters"
                || key.startsWith("pro_entitlement_") || key == "waex_pro_active"
            ) {
                scheduleReload()
            }
        }

        // Apply to any newly resumed Activity
        ActivityTracker.addListener(object : ActivityTracker.ActivityListener {
            override fun onActivityResumed(activity: Activity) {
                Handler(Looper.getMainLooper()).post {
                    applyFiltersToActivity(activity)
                }
            }
        })
    }

    private fun reloadFilters() {
        val filterItemsRaw = prefs.getString("filter_items", null) ?: ""
        val isEnabled = isEnabled("custom_filters", true)
        val next = HashMap<Int, FilterItem>()
        if (isEnabled && filterItemsRaw.isNotBlank()) {
            for (item in parseFilters(filterItemsRaw)) {
                val id = getResourceId(normalizeResourceIdName(item.id))
                if (id > 0) next[id] = item
            }
        }
        targetMap = Collections.unmodifiableMap(next)
        mainHandler.post {
            ActivityTracker.getCurrentActivity()?.let(::applyFiltersToActivity)
        }
    }

    private fun parseFilters(raw: String): List<FilterItem> {
        val result = mutableListOf<FilterItem>()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val idStr = obj.optString("id", "").trim()
                    if (idStr.isNotEmpty()) {
                        val behavior = obj.optString("behavior", FilterItem.BEHAVIOR_GONE)
                        val color = obj.optInt("color", 0xFFFF0000.toInt())
                        val opacity = obj.optInt("opacity", 100)
                        val scale = (Math.round(obj.optDouble("scale", 1.0) * 10.0) / 10.0).toFloat()
                        result.add(FilterItem(idStr, behavior, color, opacity, scale))
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("[WAEX] Failed to parse JSON filter_items: " + e.message)
            }
        } else {
            val lines = trimmed.split("\n")
            for (line in lines) {
                val idStr = line.trim()
                if (idStr.isNotEmpty()) {
                    result.add(FilterItem(idStr, FilterItem.BEHAVIOR_GONE, 0xFFFF0000.toInt(), 100, 1.0f))
                }
            }
        }
        return result
    }

    private fun getResourceId(name: String): Int {
        if (name.isEmpty()) return -1
        return try {
            val id = context.resources.getIdentifier(name, "id", context.packageName)
            if (id == 0) -1 else id
        } catch (_: Throwable) {
            -1
        }
    }

    private fun normalizeResourceIdName(resourceId: String?): String {
        if (resourceId == null) return ""
        var name = resourceId.trim()
        val colonIndex = name.lastIndexOf(':')
        if (colonIndex >= 0) name = name.substring(colonIndex + 1)
        if (name.startsWith("@")) name = name.substring(1)
        val slashIndex = name.lastIndexOf('/')
        if (slashIndex >= 0) name = name.substring(slashIndex + 1)
        return name.trim()
    }

    private fun applyFiltersToActivity(activity: Activity) {
        if (targetMap.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val root = activity.window?.decorView ?: return
        applyFiltersRecursively(root)

        if (XposedHelpers.getAdditionalInstanceField(root, "waex_filter_layout_listener") == null) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    applyFiltersRecursively(root)
                }
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            XposedHelpers.setAdditionalInstanceField(root, "waex_filter_layout_listener", listener)
        }
    }

    private fun applyFiltersRecursively(view: View) {
        val rules = targetMap
        if (rules.isEmpty()) return
        applyFiltersRecursively(view, rules)
    }

    private fun applyFiltersRecursively(view: View, rules: Map<Int, FilterItem>) {
        val id = view.id
        if (id > 0) rules[id]?.let { applyFilterToView(view, it) }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFiltersRecursively(view.getChildAt(i), rules)
            }
        }
    }

    private fun applyFilterToView(target: View, item: FilterItem) {
        if (FilterItem.BEHAVIOR_GONE == item.behavior) {
            removeFilteredView(target)
        } else {
            FilterItemsProFeature.applyProFilter(
                target,
                item.behavior,
                item.color,
                item.opacity,
                item.scale,
                prefs
            )
        }
    }

    private fun removeFilteredView(target: View) {
        if (target.visibility != View.GONE) target.visibility = View.GONE

        val lp = target.layoutParams
        if (lp != null) {
            lp.width = 0
            lp.height = 0
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.setMargins(0, 0, 0, 0)
            }
            target.layoutParams = lp
        }

        target.post {
            val parent = target.parent
            if (parent is ViewGroup) {
                try {
                    parent.removeView(target)
                } catch (_: Throwable) {}
            }
        }
    }
}
