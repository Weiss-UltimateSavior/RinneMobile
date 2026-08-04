package com.apps.chat

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherEditText
import com.core.R
import com.core.launcherbridge.LauncherAuthBridge
import com.core.launcherbridge.LlmConfig
import com.core.launcherbridge.LlmConfigCallback

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * AI 聊天页的 LLM 配置弹窗。
 * 弹窗外壳、表单构建与保存/重置行为统一收纳在此，调用方仅一行 {@code new AgentLlmConfigDialog(...).show()}。
 *
 * @param onSaveConfig  保存成功后回调（可选；原行为仅关闭弹窗并 Toast，无需回调时传 null）
 * @param onResetConfig 恢复默认成功后回调（可选，同上）
 */
class AgentLlmConfigDialog @JvmOverloads constructor(
    private val activity: Activity,
    private val onSaveConfig: Runnable? = null,
    private val onResetConfig: Runnable? = null
) {

    /**
     * 构建并展示 LLM 配置弹窗。
     *
     * <p>透明 window 外壳 + 表单（baseUrl / apiKey / model / temperature）+ 重置 / 保存按钮；
     * 弹窗宽度统一走 [LauncherDialogFactory.dialogWidthPx]，主题色调经 LauncherTheme.applyPrimaryTone 注入。
     * 异步回填当前配置（LauncherAuthBridge.fetchLlmConfig）；保存 / 重置经 LauncherAuthBridge.updateLlmConfig，
     * 成功后分别触发 {@code onSaveConfig} / {@code onResetConfig} 回调（若构造时提供）。
     */
    fun show() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        val window = dialog.window
        if (window == null) return
        // 透明 window 外壳 + 表单宽度兜底，与 LauncherDialogFactory 弹窗规范一致（禁止裸固定 dp 宽度）
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(LauncherDialogFactory.dialogWidthPx(activity, LauncherDialogFactory.WIDTH_FORM_DP), WindowManager.LayoutParams.WRAP_CONTENT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(LauncherTheme.dp(activity, 22), LauncherTheme.dp(activity, 18), LauncherTheme.dp(activity, 22), LauncherTheme.dp(activity, 15))
        root.setBackgroundResource(R.drawable.launcher_dialog_bg)
        val title = dialogText(activity.getString(R.string.social_custom_llm_title), 16, LauncherTheme.text(activity))
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)
        val info = dialogText(activity.getString(R.string.social_custom_llm_note), 11, LauncherTheme.textMuted(activity))
        info.setLineSpacing(LauncherTheme.dp(activity, 3).toFloat(), 1f)
        val infoLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        infoLp.setMargins(0, LauncherTheme.dp(activity, 9), 0, 0)
        root.addView(info, infoLp)
        val baseUrl = llmInput(root, activity.getString(R.string.social_api_endpoint), activity.getString(R.string.social_agent_api_address_hint), InputType.TYPE_TEXT_VARIATION_URI)
        val apiKey = llmInput(root, activity.getString(R.string.social_api_key), activity.getString(R.string.social_api_key_default_hint), InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = llmInput(root, activity.getString(R.string.social_model_name), activity.getString(R.string.social_model_example), InputType.TYPE_CLASS_TEXT)
        val temperature = llmInput(root, activity.getString(R.string.social_temperature), activity.getString(R.string.social_temperature_hint), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val loading = dialogText(activity.getString(R.string.social_reading_config), 11, LauncherTheme.textMuted(activity))
        val loadingLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        loadingLp.setMargins(0, LauncherTheme.dp(activity, 8), 0, 0)
        root.addView(loading, loadingLp)
        val reset = dialogText(activity.getString(R.string.social_restore_default), 12, LauncherTheme.text(activity))
        LauncherTheme.secondaryButton(reset)
        val resetLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(activity, 34))
        resetLp.setMargins(0, LauncherTheme.dp(activity, 11), 0, 0)
        root.addView(reset, resetLp)
        val buttons = LinearLayout(activity)
        buttons.orientation = LinearLayout.HORIZONTAL
        val buttonsLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(activity, 36))
        buttonsLp.setMargins(0, LauncherTheme.dp(activity, 8), 0, 0)
        val cancel = dialogText(activity.getString(R.string.social_action_cancel), 13, LauncherTheme.text(activity))
        LauncherTheme.secondaryButton(cancel)
        cancel.setOnClickListener { dialog.dismiss() }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val save = dialogText(activity.getString(R.string.social_action_save), 13, LauncherTheme.onPrimary(activity))
        LauncherTheme.primaryButton(save)
        val saveLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        saveLp.setMargins(LauncherTheme.dp(activity, 8), 0, 0, 0)
        buttons.addView(save, saveLp)
        root.addView(buttons, buttonsLp)
        val dialogScroll = ScrollView(activity)
        dialogScroll.isFillViewport = true
        dialogScroll.addView(root)
        // 主题色调统一（LauncherDialogFactory.setContent 同款处理）
        LauncherTheme.applyPrimaryTone(root)
        window.setContentView(dialogScroll)

        baseUrl.isFocusableInTouchMode = true
        baseUrl.requestFocus()
        baseUrl.postDelayed({
            if (!dialog.isShowing) return@postDelayed
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm != null) imm.showSoftInput(baseUrl, InputMethodManager.SHOW_IMPLICIT)
        }, 180L)
        baseUrl.postDelayed({
            if (!dialog.isShowing) return@postDelayed
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm != null) imm.showSoftInput(baseUrl, InputMethodManager.SHOW_FORCED)
        }, 420L)

        LauncherAuthBridge.fetchLlmConfig(activity, object : LlmConfigCallback {
            override fun onSuccess(config: LlmConfig) {
                if (!dialog.isShowing) return
                baseUrl.setText(config.baseUrl)
                apiKey.setText(config.apiKey)
                model.setText(config.model)
                temperature.setText(config.temperature)
                loading.setText(R.string.social_default_model_hint)
            }

            override fun onError(message: String) {
                if (dialog.isShowing) loading.setText(activity.getString(R.string.social_read_failed, message))
            }
        })
        reset.setOnClickListener {
            saveLlmConfig(dialog, LlmConfig(), reset,
                activity.getString(R.string.social_restoring), activity.getString(R.string.social_restore_default))
        }
        save.setOnClickListener {
            val baseUrlValue = textOf(baseUrl)
            val baseUrlError = validatePublicBaseUrl(baseUrlValue)
            if (baseUrlError != null) {
                baseUrl.error = baseUrlError
                return@setOnClickListener
            }
            val temp = textOf(temperature)
            if (!temp.isEmpty()) {
                try {
                    val value = temp.toDouble()
                    if (value < 0.0 || value > 2.0) throw NumberFormatException()
                } catch (error: NumberFormatException) {
                    temperature.error = activity.getString(R.string.social_temperature_error)
                    return@setOnClickListener
                }
            }
            val config = LlmConfig()
            config.baseUrl = baseUrlValue
            config.apiKey = textOf(apiKey)
            config.model = textOf(model)
            config.temperature = temp
            saveLlmConfig(dialog, config, save, activity.getString(R.string.social_validating_and_saving),
                activity.getString(R.string.social_action_save))
        }
    }

    private fun llmInput(root: LinearLayout, label: String, hintText: String, inputType: Int): EditText {
        val labelView = dialogText(label, 12, LauncherTheme.text(activity))
        labelView.setTypeface(null, Typeface.BOLD)
        val labelLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        labelLp.setMargins(0, LauncherTheme.dp(activity, 10), 0, 0)
        root.addView(labelView, labelLp)
        val field = LauncherEditText(activity)
        field.setSingleLine(true)
        field.setTextSize(12f)
        field.setInputType(inputType)
        field.hint = hintText
        field.setTextColor(LauncherTheme.text(activity))
        field.setHintTextColor(LauncherTheme.textMuted(activity))
        field.setBackgroundResource(R.drawable.launcher_account_input)
        LauncherTheme.styleTextInput(field)
        field.setPadding(LauncherTheme.dp(activity, 13), 0, LauncherTheme.dp(activity, 13), 0)
        root.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(activity, 38)))
        return field
    }

    private fun saveLlmConfig(dialog: Dialog, config: LlmConfig, action: TextView,
                              loadingText: String, idleText: String) {
        action.isEnabled = false
        action.text = loadingText
        val restoresDefault = config.baseUrl.trim().isEmpty()
                && config.apiKey.trim().isEmpty()
                && config.model.trim().isEmpty()
                && config.temperature.trim().isEmpty()
        LauncherAuthBridge.updateLlmConfig(activity, config, object : LlmConfigCallback {
            override fun onSuccess(config: LlmConfig) {
                if (dialog.isShowing) dialog.dismiss()
                Toast.makeText(activity,
                    if (restoresDefault) R.string.social_default_model_restored
                    else R.string.social_model_saved, Toast.LENGTH_SHORT).show()
                if (restoresDefault) {
                    onResetConfig?.run()
                } else {
                    onSaveConfig?.run()
                }
            }

            override fun onError(message: String) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    action.isEnabled = true
                    action.text = idleText
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** Client-side UX guard only; the server remains the authoritative URL security boundary. */
    private fun validatePublicBaseUrl(value: String?): String? {
        if (value.isNullOrEmpty() || value.trim().isEmpty()) return null
        try {
            val uri = URI(value.trim())
            val scheme = uri.scheme
            val host = uri.host
            if (!"http".equals(scheme, true) && !"https".equals(scheme, true)) return activity.getString(R.string.social_error_http_only)
            if (uri.userInfo != null) return activity.getString(R.string.social_error_endpoint_credentials)
            if (host == null || host.trim().isEmpty()) return activity.getString(R.string.social_error_public_endpoint)
            val normalized = host.lowercase(Locale.ROOT)
            if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")
                    || normalized == "0.0.0.0" || normalized == "::1" || normalized.startsWith("fe80:")
                    || normalized.startsWith("fc") || normalized.startsWith("fd")) return activity.getString(R.string.social_error_private_endpoint)
            val parts = normalized.split('.').dropLastWhile { it.isEmpty() }
            var ipv4Literal = parts.size == 4
            for (part in parts) if (!part.matches(Regex("\\d+"))) ipv4Literal = false
            if (ipv4Literal) {
                val first = parts[0].toInt()
                val second = parts[1].toInt()
                if (first == 10 || first == 127 || first == 0 || first == 169 && second == 254
                        || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168) {
                    return activity.getString(R.string.social_error_private_endpoint)
                }
            }
            return null
        } catch (error: URISyntaxException) {
            return activity.getString(R.string.social_error_http_endpoint)
        } catch (error: NumberFormatException) {
            return activity.getString(R.string.social_error_http_endpoint)
        }
    }

    private fun dialogText(text: String, size: Int, color: Int): TextView {
        val view = TextView(activity)
        view.text = text
        view.gravity = Gravity.CENTER
        view.setTextColor(color)
        view.setTextSize(size.toFloat())
        return view
    }

    private fun textOf(view: TextView): String = view.text?.toString()?.trim() ?: ""
}
