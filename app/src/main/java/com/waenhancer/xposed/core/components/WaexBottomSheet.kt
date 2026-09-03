package com.waenhancer.xposed.core.components

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Modern Native WhatsApp WDS (WhatsApp Design System) bottom sheet component for WAEX.
 * Supports title, optional top-right action/toggle view, scrollable content area,
 * WDS buttons, WDSSwitch, and physics-based slide gestures and animations.
 */
class WaexBottomSheet(private val context: Context) {

    private var titleText: CharSequence? = null
    private var topRightView: View? = null
    private var messageText: CharSequence? = null
    private var positiveButtonText: CharSequence? = null
    private var positiveListener: DialogInterface.OnClickListener? = null
    private var negativeButtonText: CharSequence? = null
    private var negativeListener: DialogInterface.OnClickListener? = null
    private var customView: View? = null
    private var items: Array<CharSequence>? = null
    private var itemsListener: DialogInterface.OnClickListener? = null
    private var selectedIndex: Int = -1
    private var multiChoiceItems: Array<CharSequence>? = null
    private var checkedItems: BooleanArray? = null
    private var multiChoiceListener: DialogInterface.OnMultiChoiceClickListener? = null
    private var isBottomSheet: Boolean = true

    private var createdDialog: Dialog? = null

    fun setTitle(title: CharSequence?): WaexBottomSheet {
        this.titleText = title
        return this
    }

    fun setTopRightView(view: View?): WaexBottomSheet {
        this.topRightView = view
        return this
    }

    fun setMessage(message: CharSequence?): WaexBottomSheet {
        this.messageText = message
        return this
    }

    fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): WaexBottomSheet {
        this.positiveButtonText = text
        this.positiveListener = listener
        return this
    }

    fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): WaexBottomSheet {
        this.negativeButtonText = text
        this.negativeListener = listener
        return this
    }

    fun setView(view: View?): WaexBottomSheet {
        this.customView = view
        return this
    }

    fun setItems(items: Array<CharSequence>?, listener: DialogInterface.OnClickListener?): WaexBottomSheet {
        this.items = items
        this.itemsListener = listener
        return this
    }

    fun setSingleChoiceItems(items: Array<CharSequence>?, checkedItem: Int, listener: DialogInterface.OnClickListener?): WaexBottomSheet {
        this.items = items
        this.selectedIndex = checkedItem
        this.itemsListener = listener
        return this
    }

    fun setMultiChoiceItems(items: Array<CharSequence>?, checkedItems: BooleanArray?, listener: DialogInterface.OnMultiChoiceClickListener?): WaexBottomSheet {
        this.multiChoiceItems = items
        this.checkedItems = checkedItems
        this.multiChoiceListener = listener
        return this
    }

    fun asBottomSheet(): WaexBottomSheet {
        this.isBottomSheet = true
        return this
    }

    fun create(): Dialog {
        if (createdDialog != null) return createdDialog!!

        if (!isBottomSheet) {
            return AlertDialog.Builder(context).apply {
                titleText?.let { setTitle(it) }
                messageText?.let { setMessage(it) }
                positiveButtonText?.let { setPositiveButton(it, positiveListener) }
                negativeButtonText?.let { setNegativeButton(it, negativeListener) }
                customView?.let { setView(it) }
            }.create().also { createdDialog = it }
        }

        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxScrollHeight = (screenHeight * 0.58f).toInt()

        val isDark = isDarkTheme()
        val bgSurfaceColor = if (isDark) 0xFF12181C.toInt() else 0xFFFFFFFF.toInt()
        val primaryTextColor = if (isDark) 0xFFE9EDEF.toInt() else 0xFF111B21.toInt()
        val secondaryTextColor = if (isDark) 0xFF8696A0.toInt() else 0xFF667781.toInt()
        val accentColor = if (isDark) 0xFF21C063.toInt() else 0xFF008069.toInt()

        // Root Dimmed Overlay
        val container = RelativeLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Main Sheet Layout
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(28))
            val lp = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT)
            lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            layoutParams = lp

            val bg = GradientDrawable().apply {
                setColor(bgSurfaceColor)
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            background = bg
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(8).toFloat()
        }

        // 1. Drag Handle
        val dragHandle = View(context).apply {
            val handleLp = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            layoutParams = handleLp
            background = GradientDrawable().apply {
                setColor(secondaryTextColor and 0x33FFFFFF.toInt() or 0x33000000)
                cornerRadius = dp(2).toFloat()
            }
        }
        mainLayout.addView(dragHandle)

        // Slide-down dismiss helper
        val dismissWithAnimation: () -> Unit = {
            mainLayout.animate()
                .translationY(screenHeight.toFloat())
                .setDuration(220)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { dialog.dismiss() }
                .start()
        }

        // Touch gesture for downward drag dismiss
        val dragListener = object : View.OnTouchListener {
            private var initialY = 0f
            private var initialTranslationY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = event.rawY
                        initialTranslationY = mainLayout.translationY
                        isDragging = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isDragging) return false
                        val deltaY = event.rawY - initialY
                        mainLayout.translationY = Math.max(0f, initialTranslationY + deltaY)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        val currentY = mainLayout.translationY
                        if (currentY > mainLayout.height / 3.5f || currentY > dp(120)) {
                            dismissWithAnimation()
                        } else {
                            mainLayout.animate()
                                .translationY(0f)
                                .setDuration(200)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                        return true
                    }
                }
                return false
            }
        }
        dragHandle.setOnTouchListener(dragListener)

        // 2. Header Area (Title + Optional Top-Right View)
        if (!titleText.isNullOrEmpty() || topRightView != null) {
            val headerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }

            if (!titleText.isNullOrEmpty()) {
                val titleView = createWdsTextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    text = titleText
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(primaryTextColor)
                    gravity = if (topRightView != null) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
                }
                headerLayout.addView(titleView)
            }

            topRightView?.let { rView ->
                (rView.parent as? ViewGroup)?.removeView(rView)
                headerLayout.addView(rView)
            }

            mainLayout.addView(headerLayout)
        }

        // 3. Scrollable Content Area with max height constraint
        val scrollView = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Message text
        if (!messageText.isNullOrEmpty()) {
            val msgView = createWdsTextView(context).apply {
                text = messageText
                textSize = 14.5f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            }
            scrollContent.addView(msgView)
        }

        // Custom View (Sticker Preview, Edit History list, etc.)
        customView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            val customParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
            view.layoutParams = customParams
            scrollContent.addView(view)
        }

        // Single Choice Items
        items?.let { itemList ->
            val itemsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (i in itemList.indices) {
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setPadding(dp(16), dp(12), dp(16), dp(12))

                    val label = createWdsTextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                        text = itemList[i]
                        textSize = 16f
                        setTextColor(primaryTextColor)
                    }
                    addView(label)

                    if (selectedIndex >= 0) {
                        val radio = RadioButton(context).apply {
                            isChecked = (i == selectedIndex)
                            isClickable = false
                            if (isDark) {
                                buttonTintList = ColorStateList(
                                    arrayOf(intArrayOf(-android.R.attr.state_checked), intArrayOf(android.R.attr.state_checked)),
                                    intArrayOf(0xFF8696A0.toInt(), accentColor)
                                )
                            }
                        }
                        addView(radio)
                    }

                    background = RippleDrawable(
                        ColorStateList.valueOf(secondaryTextColor and 0x15FFFFFF.toInt() or 0x15000000),
                        ColorDrawable(Color.TRANSPARENT),
                        null
                    )
                    setOnClickListener {
                        itemsListener?.onClick(dialog, i)
                        dismissWithAnimation()
                    }
                }
                itemsLayout.addView(itemRow)
            }
            scrollContent.addView(itemsLayout)
        }

        // Multi Choice Items
        multiChoiceItems?.let { mItems ->
            val itemsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (i in mItems.indices) {
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setPadding(dp(16), dp(12), dp(16), dp(12))

                    val label = createWdsTextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                        text = mItems[i]
                        textSize = 16f
                        setTextColor(primaryTextColor)
                    }
                    addView(label)

                    val switchView = createWdsSwitch(context).apply {
                        isChecked = (checkedItems != null && i < checkedItems!!.size && checkedItems!![i])
                        isClickable = false
                    }
                    addView(switchView)

                    background = RippleDrawable(
                        ColorStateList.valueOf(secondaryTextColor and 0x15FFFFFF.toInt() or 0x15000000),
                        ColorDrawable(Color.TRANSPARENT),
                        null
                    )
                    setOnClickListener {
                        val checked = !switchView.isChecked
                        switchView.isChecked = checked
                        checkedItems?.let { if (i < it.size) it[i] = checked }
                        multiChoiceListener?.onClick(dialog, i, checked)
                    }
                }
                itemsLayout.addView(itemRow)
            }
            scrollContent.addView(itemsLayout)
        }

        scrollView.addView(scrollContent)
        mainLayout.addView(scrollView)

        // 4. Vertical Stacked WDS Action Buttons
        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            }
        }

        // Positive Button — WDSButton FILLED
        if (!positiveButtonText.isNullOrEmpty()) {
            val posBtn = createWdsButton(context, positiveButtonText!!, "FILLED") ?: run {
                createWdsTextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                    text = positiveButtonText
                    gravity = Gravity.CENTER
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                    background = RippleDrawable(
                        ColorStateList.valueOf(0x22FFFFFF),
                        GradientDrawable().apply {
                            setColor(accentColor)
                            cornerRadius = dp(24).toFloat()
                        },
                        null
                    )
                    setTextColor(if (isDark) Color.BLACK else Color.WHITE)
                }
            }
            val posLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (!negativeButtonText.isNullOrEmpty()) bottomMargin = dp(8)
            }
            posBtn.isClickable = true
            posBtn.isFocusable = true
            posBtn.setOnClickListener {
                XposedBridge.log("[WAEX] Positive button onClick triggered!")
                try {
                    positiveListener?.onClick(dialog, DialogInterface.BUTTON_POSITIVE)
                } catch (t: Throwable) {
                    XposedBridge.log("[WAEX] Error in positiveListener: ${t.message}")
                }
                dismissWithAnimation()
            }
            buttonsLayout.addView(posBtn)
        }

        // Negative Button — WDSButton OUTLINE
        if (!negativeButtonText.isNullOrEmpty()) {
            val negBtn = createWdsButton(context, negativeButtonText!!, "OUTLINE") ?: run {
                createWdsTextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                    text = negativeButtonText
                    gravity = Gravity.CENTER
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(accentColor)
                }
            }
            negBtn.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            negBtn.setOnClickListener {
                negativeListener?.onClick(dialog, DialogInterface.BUTTON_NEGATIVE)
                dismissWithAnimation()
            }
            buttonsLayout.addView(negBtn)
        }

        mainLayout.addView(buttonsLayout)
        container.addView(mainLayout)

        // Dismiss when tapping the dimmed scrim background
        container.setOnClickListener { dismissWithAnimation() }
        mainLayout.setOnClickListener {}

        dialog.setContentView(container)

        dialog.window?.let { window ->
            window.setGravity(Gravity.BOTTOM)
            window.decorView.setPadding(0, 0, 0, 0)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.6f)
        }

        // Native WhatsApp Bottom Sheet Entrance Slide-Up Animation & max height check
        dialog.setOnShowListener {
            mainLayout.post {
                val measuredH = mainLayout.height
                if (measuredH > maxScrollHeight) {
                    val lp = scrollView.layoutParams
                    lp.height = maxScrollHeight - dp(110)
                    scrollView.layoutParams = lp
                }
            }
            mainLayout.translationY = screenHeight.toFloat()
            mainLayout.animate()
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }

        createdDialog = dialog
        return dialog
    }

    fun show(): Dialog? {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) {
            return null
        }
        val d = create()
        try {
            d.show()
        } catch (t: Throwable) {
            XposedBridge.log("[WAEX] WaexBottomSheet.show() failed: ${t.message}")
        }
        return d
    }

    fun dismiss() {
        createdDialog?.dismiss()
    }

    private fun isDarkTheme(): Boolean {
        return try {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mode == Configuration.UI_MODE_NIGHT_YES
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun createWdsTextView(ctx: Context): TextView {
        return try {
            val clazz = ctx.classLoader.loadClass("com.whatsapp.ui.wds.components.textview.WDSTextView")
            clazz.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(ctx, null) as TextView
        } catch (t: Throwable) {
            TextView(ctx)
        }
    }

    private fun createWdsSwitch(ctx: Context): CompoundButton {
        return try {
            val clazz = ctx.classLoader.loadClass("com.whatsapp.ui.wds.components.toggle.WDSSwitch")
            clazz.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(ctx, null) as CompoundButton
        } catch (t: Throwable) {
            try {
                val materialSwitchClass = ctx.classLoader.loadClass("com.google.android.material.materialswitch.MaterialSwitch")
                materialSwitchClass.getConstructor(Context::class.java).newInstance(ctx) as CompoundButton
            } catch (t2: Throwable) {
                Switch(ctx)
            }
        }
    }

    private fun createWdsButton(ctx: Context, text: CharSequence, variant: String): View? {
        return try {
            val clazz = ctx.classLoader.loadClass("com.whatsapp.ui.wds.components.button.WDSButton")
            val button = clazz.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(ctx, null) as View
            (button as TextView).text = text
            try {
                var variantClass: Class<*>? = null
                for (m in clazz.declaredMethods) {
                    if (m.name == "setVariant" && m.parameterTypes.size == 1) {
                        variantClass = m.parameterTypes[0]
                        break
                    }
                }
                if (variantClass != null) {
                    val enumConstants = variantClass.enumConstants as? Array<*>
                    val enumVal = enumConstants?.firstOrNull { (it as? Enum<*>)?.name.equals(variant, ignoreCase = true) }
                    if (enumVal != null) {
                        XposedHelpers.callMethod(button, "setVariant", enumVal)
                    }
                }
            } catch (ignored: Throwable) {}
            button
        } catch (t: Throwable) {
            null
        }
    }
}
