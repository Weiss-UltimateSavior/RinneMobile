package com.apps.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.apps.LauncherActivity
import com.apps.LauncherThemeStyle
import com.core.R
import com.core.launcherbridge.LauncherUpdateBridge
import kotlin.math.max
import kotlin.math.min

object LauncherTheme {

    private fun uiContext(context: Context): Context {
        val wrapped = LauncherActivity.wrapLauncherUiMode(context)
        return wrapped ?: context
    }

    private fun color(context: Context, colorResId: Int): Int {
        return ContextCompat.getColor(uiContext(context), colorResId)
    }

    @JvmStatic
    fun primary(context: Context): Int {
        return LauncherActivity.launcherPrimaryColor(context)
    }

    @JvmStatic
    fun onPrimary(context: Context): Int = color(context, R.color.launcher_on_primary_color)

    @JvmStatic
    fun card(context: Context): Int = color(context, R.color.launcher_card_color)

    @JvmStatic
    fun bg(context: Context): Int = color(context, R.color.launcher_bg_color)

    @JvmStatic
    fun line(context: Context): Int = color(context, R.color.launcher_line_color)

    @JvmStatic
    fun text(context: Context): Int = color(context, R.color.launcher_text_color)

    @JvmStatic
    fun textMuted(context: Context): Int = color(context, R.color.launcher_text_muted_color)

    @JvmStatic
    fun primaryText(context: Context): Int = color(context, R.color.launcher_primary_color)

    @JvmStatic
    fun danger(context: Context): Int = color(context, R.color.launcher_danger_color)

    @JvmStatic
    fun onDanger(context: Context): Int = color(context, R.color.launcher_on_danger_color)

    @JvmStatic
    fun primaryButton(context: Context, radiusDp: Float): GradientDrawable {
        if (LauncherActivity.isXinhaitianTheme(context)) {
            return xinhaitianGradient(context, radiusDp, false)
        }
        return solidPrimary(context, radiusDp)
    }

    /** Primary tone without theme-specific gradients. */
    @JvmStatic
    fun solidPrimary(context: Context, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(primary(context))
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    /** Theme-colored card copy overlay with the same opacity as launcher_game_text_overlay. */
    @JvmStatic
    fun primaryTextOverlay(context: Context): GradientDrawable {
        val drawable = primaryButton(context, 0f)
        drawable.setAlpha(0xD9)
        return drawable
    }

    @JvmStatic
    fun secondaryButton(context: Context, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(card(context))
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    @JvmStatic
    fun dangerButton(context: Context, radiusDp: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(danger(context))
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    @JvmStatic
    fun primaryGradientCard(context: Context, radiusDp: Float): GradientDrawable {
        val baseColor = primary(context)
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                shiftColor(baseColor, 0.76f),
                baseColor,
                shiftColor(baseColor, 1.18f)
            )
        )
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    /** Outgoing messages use the active tone; incoming messages use the neutral card surface. */
    @JvmStatic
    fun chatBubble(context: Context, outgoing: Boolean): GradientDrawable {
        return if (outgoing) primaryButton(context, 18f) else secondaryButton(context, 18f)
    }

    @JvmStatic
    fun selectedChip(context: Context): GradientDrawable = primaryButton(context, 999f)

    @JvmStatic
    fun cancelChip(context: Context): GradientDrawable = secondaryButton(context, 999f)

    @JvmStatic
    fun selectedOption(context: Context): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(card(context))
        drawable.cornerRadius = dp(context, 9f).toFloat()
        return drawable
    }

    @JvmStatic
    fun circle(context: Context): GradientDrawable {
        if (LauncherActivity.isXinhaitianTheme(context)) {
            return xinhaitianGradient(context, 0f, true)
        }
        return circle(context, primary(context))
    }

    @JvmStatic
    fun circle(context: Context, color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
        return drawable
    }

    /** Navigation launch button with a 3dp, 60%-opaque ring in the active primary tone. */
    @JvmStatic
    fun circleWithSoftShadow(context: Context): Drawable {
        val ring = circle(context, ColorUtils.setAlphaComponent(primary(context), 0x99))
        val center = circle(context)
        val inset = dp(context, 3f)
        return LayerDrawable(arrayOf<Drawable>(ring, center)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    /** Circle with card background color, matching the white-card style of manage rows. */
    @JvmStatic
    fun cardCircle(context: Context): GradientDrawable = circle(context, card(context))

    /**
     * 统一应用右上角圆形按钮样式：cardCircle 背景 + 深色模式白色 tint / 浅色模式原色。
     * 供 LauncherHomeFragment.actionProfileMenu 和 LauncherProfileFragment.actionChangeCover 复用。
     */
    @JvmStatic
    fun applyCardCircleIcon(view: ImageView?, context: Context) {
        if (view == null) return
        view.background = cardCircle(context)
        if (LauncherActivity.isLauncherDarkMode(context)) {
            view.setColorFilter(Color.WHITE)
        } else {
            view.clearColorFilter()
        }
    }

    @JvmStatic
    fun xinhaitianCircle(context: Context): GradientDrawable = xinhaitianGradient(context, 0f, true)

    private fun xinhaitianGradient(context: Context, radiusDp: Float, oval: Boolean): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                LauncherThemeStyle.XINHAITIAN_PRIMARY_COLOR,
                LauncherThemeStyle.XINHAITIAN_ACCENT_COLOR
            )
        )
        drawable.shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!oval) drawable.cornerRadius = dp(context, radiusDp).toFloat()
        return drawable
    }

    @JvmStatic
    fun statsScrim(context: Context): GradientDrawable = statsScrim(primary(context))

    @JvmStatic
    fun statsScrim(baseColor: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(230, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                Color.argb(179, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            )
        )
    }

    @JvmStatic
    fun textPrimary(view: TextView?) {
        if (view != null) view.setTextColor(primary(view.context))
    }

    @JvmStatic
    fun textOnPrimary(view: TextView?) {
        if (view != null) view.setTextColor(onPrimary(view.context))
    }

    @JvmStatic
    fun chip(view: TextView?, selected: Boolean) {
        if (view == null) return
        view.setTextColor(if (selected) onPrimary(view.context) else primary(view.context))
        view.background = if (selected) selectedChip(view.context) else secondaryButton(view.context, 999f)
    }

    @JvmStatic
    fun primaryButton(view: TextView?) {
        if (view == null) return
        view.setTextColor(onPrimary(view.context))
        view.background = primaryButton(view.context, 20f)
    }

    /** Applies the common full-width action treatment used by Launcher setting pages. */
    @JvmStatic
    fun longActionButton(view: TextView?) {
        if (view == null) return
        view.gravity = Gravity.CENTER
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        view.setTypeface(null, Typeface.BOLD)
        primaryButton(view)
    }

    /** Applies the compact form of the shared Launcher action treatment. */
    @JvmStatic
    fun shortActionButton(view: TextView?) {
        longActionButton(view)
    }

    /** Applies the compact secondary action treatment while preserving shared button metrics. */
    @JvmStatic
    fun shortSecondaryActionButton(view: TextView?) {
        if (view == null) return
        view.gravity = Gravity.CENTER
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        view.setTypeface(null, Typeface.BOLD)
        secondaryButton(view)
    }

    /** Normalizes ordinary page form fields; call only from non-dialog page roots. */
    @JvmStatic
    fun formInputs(vararg views: EditText?) {
        for (view in views) {
            if (view == null) continue
            val context = view.context
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            view.setPaddingRelative(dp(context, 13f), view.paddingTop, dp(context, 13f), view.paddingBottom)
            view.background = secondaryButton(context, 20f)
            val inputType = view.inputType
            val multiline = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            if (!multiline && view.layoutParams != null) {
                view.layoutParams.height = dp(context, 45f)
                view.requestLayout()
            }
            styleTextInput(view)
        }
    }

    @JvmStatic
    fun secondaryButton(view: TextView?) {
        if (view == null) return
        view.setTextColor(primary(view.context))
        view.background = secondaryButton(view.context, 20f)
    }

    @JvmStatic
    fun dangerButton(view: TextView?) {
        if (view == null) return
        view.setTextColor(onDanger(view.context))
        view.background = dangerButton(view.context, 20f)
    }

    @JvmStatic
    fun menuItem(view: TextView?) {
        if (view == null) return
        view.setTextColor(primary(view.context))
        view.background = secondaryButton(view.context, 999f)
    }

    @JvmStatic
    fun dangerMenuItem(view: TextView?) {
        if (view == null) return
        view.setTextColor(danger(view.context))
        view.background = secondaryButton(view.context, 999f)
    }

    @JvmStatic
    fun styleSpinner(spinner: Spinner?) {
        if (spinner == null) return
        val context = spinner.context
        spinner.background = secondaryButton(context, 20f)
        // dropdown 容器使用与弹窗一致的圆角背景
        spinner.setPopupBackgroundResource(R.drawable.launcher_spinner_popup_bg)
    }

    /**
     * 统一 SwitchCompat 启停按钮的色调：开启时使用主题主色，关闭时使用中性灰。
     * 必须在 Activity 创建后调用，确保主题已加载。
     */
    @JvmStatic
    fun styleSwitch(switchCompat: SwitchCompat?) {
        if (switchCompat == null) return
        val context = switchCompat.context
        val primary = primary(context)
        val mutedGray = ContextCompat.getColor(context, R.color.launcher_text_muted_color)

        // thumb：开关圆点。开启时主色，关闭时浅灰
        val thumbStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val thumbColors = intArrayOf(primary, mutedGray)
        switchCompat.thumbTintList = ColorStateList(thumbStates, thumbColors)

        // track：开关轨道。开启时半透明主色，关闭时更浅的灰
        val trackOn = blend(primary, Color.WHITE, 0.6f)
        val trackOff = blend(mutedGray, Color.WHITE, 0.6f)
        val trackStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val trackColors = intArrayOf(trackOn, trackOff)
        switchCompat.trackTintList = ColorStateList(trackStates, trackColors)
    }

    /**
     * Material 3 风格开关：开启时为实色轨道与白色圆点，关闭时使用描边轨道。
     * 仅用于需要较大、醒目的设置页开关。
     */
    @JvmStatic
    fun styleMaterialSwitch(switchCompat: SwitchCompat?) {
        if (switchCompat == null) return
        val context = switchCompat.context
        val primary = primary(context)
        val mutedGray = ContextCompat.getColor(context, R.color.launcher_text_muted_color)
        val density = context.resources.displayMetrics.density
        val trackWidth = (49f * density).toInt()
        val trackHeight = (29f * density).toInt()
        val thumbSize = (21f * density).toInt()
        val strokeWidth = max(1, (2f * density).toInt())

        switchCompat.showText = false
        switchCompat.splitTrack = false
        switchCompat.switchMinWidth = trackWidth
        // 保留点击行为，但去除 SwitchCompat 默认的按下波纹与背景高亮。
        switchCompat.background = null
        // SwitchCompat 会对独立 thumb 进行内部偏移和裁切；将轨道与圆点作为同一个
        // Drawable 绘制，确保关闭时的圆点保持完整圆形。
        switchCompat.trackDrawable = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private var isChecked = false

            override fun draw(canvas: android.graphics.Canvas) {
                val bounds = bounds
                val radius = bounds.height() / 2f
                val centerY = bounds.exactCenterY()
                val horizontalInset = (4f * density)
                val thumbRadius = thumbSize / 2f
                val thumbCenterX = if (isChecked) {
                    bounds.right - horizontalInset - thumbRadius
                } else {
                    bounds.left + horizontalInset + thumbRadius
                }

                paint.style = android.graphics.Paint.Style.FILL
                paint.color = if (isChecked) primary else Color.TRANSPARENT
                canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                        bounds.bottom.toFloat(), radius, radius, paint)
                if (!isChecked) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = strokeWidth.toFloat()
                    paint.color = mutedGray
                    canvas.drawRoundRect(bounds.left + strokeWidth / 2f, bounds.top + strokeWidth / 2f,
                            bounds.right - strokeWidth / 2f, bounds.bottom - strokeWidth / 2f,
                            radius, radius, paint)
                }
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = if (isChecked) Color.WHITE else mutedGray
                canvas.drawCircle(thumbCenterX, centerY, thumbRadius, paint)
            }

            override fun isStateful() = true

            override fun onStateChange(stateSet: IntArray): Boolean {
                val checked = stateSet.any { it == android.R.attr.state_checked }
                if (isChecked == checked) return false
                isChecked = checked
                invalidateSelf()
                return true
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth() = trackWidth
            override fun getIntrinsicHeight() = trackHeight
        }
        switchCompat.thumbDrawable = null
        switchCompat.thumbTintList = null
        switchCompat.trackTintList = null
    }

    /** Applies the active Launcher tone to a text input's insertion cursor. */
    @JvmStatic
    fun styleTextInput(input: EditText?) {
        if (input == null) return
        val primary = primary(input.context)
        input.highlightColor = ColorUtils.setAlphaComponent(primary, 82)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cursor = GradientDrawable()
        cursor.setColor(primary)
        cursor.setSize(dp(input.context, 2f), -1)
        input.setTextCursorDrawable(cursor)
        input.setTextSelectHandle(selectionHandle(input.context, primary))
        input.setTextSelectHandleLeft(selectionHandle(input.context, primary))
        input.setTextSelectHandleRight(selectionHandle(input.context, primary))
    }

    private fun selectionHandle(context: Context, color: Int): GradientDrawable {
        val handle = GradientDrawable()
        handle.shape = GradientDrawable.OVAL
        handle.setColor(color)
        val size = dp(context, 18f)
        handle.setSize(size, size)
        return handle
    }

    private fun blend(color1: Int, color2: Int, ratio: Float): Int {
        val r = (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    @JvmStatic
    fun <T> spinnerAdapter(context: Context, items: Array<T>): ArrayAdapter<T> {
        return object : ArrayAdapter<T>(context, R.layout.spinner_item_themed, items) {
            @NonNull
            override fun getView(position: Int, convertView: View?, @NonNull parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                styleSpinnerItemView(view, false)
                return view
            }

            @NonNull
            override fun getDropDownView(position: Int, convertView: View?, @NonNull parent: ViewGroup): View {
                val view = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.spinner_dropdown_themed, parent, false)
                if (view is TextView) {
                    view.text = getItem(position)?.toString() ?: "null"
                }
                styleSpinnerItemView(view, true)
                return view
            }
        }
    }

    @JvmStatic
    fun dialogButtons(cancel: TextView?, confirm: TextView?) {
        if (cancel != null) {
            secondaryButton(cancel)
        }
        primaryButton(confirm)
    }

    @JvmStatic
    fun applyPrimaryTone(root: View?) {
        if (root == null) return
        val context = root.context
        val defaultPrimary = primaryText(context)
        val themedPrimary = primary(context)

        if (root is TextView) {
            if (root.currentTextColor == defaultPrimary) {
                root.setTextColor(themedPrimary)
            }
        }
        if (root is CompoundButton) {
            root.buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(themedPrimary, textMuted(context))
            )
        }
        if (root is EditText) {
            styleTextInput(root)
        }

        val idName = idName(root)
        if (isPrimaryButtonId(idName) && root is TextView) {
            primaryButton(root)
        } else if (isSecondaryButtonId(idName) && root is TextView) {
            secondaryButton(root)
        } else if (isDangerButtonId(idName) && root is TextView) {
            dangerButton(root)
        }

        if (root is ViewGroup) {
            val group = root
            for (i in 0 until group.childCount) {
                applyPrimaryTone(group.getChildAt(i))
            }
        }
    }

    /** Applies the shared icon and arrow treatment used by Launcher action rows. */
    @JvmStatic
    fun styleManageRow(row: View?) {
        if (row !is ViewGroup) return
        val context = row.context
        val group: ViewGroup = row
        if (group.childCount > 0 && group.getChildAt(0) is TextView) {
            val icon = group.getChildAt(0) as TextView
            icon.background = circle(context)
            icon.setTextColor(onPrimary(context))
        } else if (group.childCount > 0 && group.getChildAt(0) is ImageView) {
            val icon = group.getChildAt(0) as ImageView
            icon.background = null
            icon.imageTintList = ColorStateList.valueOf(primary(context))
        }
        if (group.childCount > 2 && group.getChildAt(2) is ImageView) {
            (group.getChildAt(2) as ImageView).imageTintList =
                ColorStateList.valueOf(primary(context))
        }
    }

    @JvmStatic
    fun idName(view: View?): String {
        if (view == null || view.id == View.NO_ID) return ""
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (ignored: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun dp(context: Context, value: Float): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun isPrimaryButtonId(idName: String?): Boolean {
        if (idName == null) return false
        return "btnSubmit" == idName
            || "addGameSave" == idName
            || "aiDetailClose" == idName
            || "aiGenerateSubmit" == idName
            || "aiHistoryClear" == idName
            || "aiReviewGenerate" == idName
            || "aiReviewSave" == idName
            || "btnSave" == idName
            || "registerCreate" == idName
            || "chatSelectContinue" == idName
            || "disclaimerClose" == idName
            || "imagePreviewShare" == idName
            || "themeMenuApply" == idName
            || "pendingClose" == idName
    }

    private fun isSecondaryButtonId(idName: String?): Boolean {
        if (idName == null) return false
        return "aiReviewHistory" == idName
            || "aiGenerateHistory" == idName
            || "btnCancel" == idName
            || "btnPickCover" == idName
            || "imagePreviewClose" == idName
            || "imagePreviewSave" == idName
    }

    private fun isDangerButtonId(idName: String?): Boolean {
        return "dialogDangerButton" == idName
    }

    private fun styleSpinnerItemView(view: View, dropdown: Boolean) {
        if (view !is TextView) return
        val textView = view
        val context = textView.context
        textView.setTextColor(text(context))
        if (dropdown) {
            // dropdown item 透明背景，让 popup 容器的圆角背景统一显示
            textView.setBackgroundColor(Color.TRANSPARENT)
            textView.setPadding(dp(context, 13f), 0, dp(context, 13f), 0)
        } else {
            textView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun shiftColor(color: Int, factor: Float): Int {
        return Color.rgb(
            clamp(Math.round(Color.red(color) * factor)),
            clamp(Math.round(Color.green(color) * factor)),
            clamp(Math.round(Color.blue(color) * factor))
        )
    }

    private fun clamp(value: Int): Int {
        return max(0, min(255, value))
    }

    @JvmStatic
    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    @JvmStatic
    fun showUpdateResultDialog(
        context: Context, info: LauncherUpdateBridge.UpdateInfo?,
        currentVersion: String?, hasUpdate: Boolean, error: String?
    ) {
        LauncherDialogFactory.showUpdateResult(context, info, currentVersion, hasUpdate, error)
    }
}
