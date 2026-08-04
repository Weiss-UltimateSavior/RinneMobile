package com.apps.game

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.R
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * 九宫格数字密码弹窗，支持设置密码（两次确认）和验证密码两种模式。
 * 密码为 6 位纯数字，存储时使用 SHA-256 哈希。
 */
object GamePasswordDialog {

    private const val TAG = "GamePasswordDialog"
    private const val PASSWORD_LENGTH = 6
    private const val MODE_SET = 0
    private const val MODE_VERIFY = 1

    fun interface OnPasswordSetListener {
        fun onPasswordSet(hashedPassword: String)
    }

    /** 设置密码弹窗：输入一次 → 再次确认 → 一致则回调 */
    @JvmStatic
    fun showSetDialog(context: Context, gameTitle: String, listener: OnPasswordSetListener) {
        show(context, gameTitle, MODE_SET, null, listener, null)
    }

    /** 验证密码弹窗：输入一次 → 与 hashedPassword 比对 → 正确则回调 */
    @JvmStatic
    fun showVerifyDialog(context: Context, gameTitle: String, hashedPassword: String?, onSuccess: Runnable?) {
        show(context, gameTitle, MODE_VERIFY, hashedPassword, null, onSuccess)
    }

    /** SHA-256 哈希，结果转 hex 字符串。失败时抛出异常，禁止返回明文以避免明文落库。 */
    @JvmStatic
    fun hash(raw: String): String {
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        } catch (e: NoSuchAlgorithmException) {
            Log.e(TAG, "hash failed, refusing to return raw password", e)
            throw IllegalStateException("Failed to encrypt password", e)
        }
    }

    private fun show(context: Context, gameTitle: String, mode: Int,
                     hashedPassword: String?, setListener: OnPasswordSetListener?, verifySuccess: Runnable?) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val landscape = context.resources.displayMetrics.widthPixels > context.resources.displayMetrics.heightPixels
        val padH = LauncherTheme.dp(context, if (landscape) 20 else 24)
        val padV = LauncherTheme.dp(context, if (landscape) 16 else 28)
        val btnSize = LauncherTheme.dp(context, if (landscape) 42 else 56)
        val btnSpacing = LauncherTheme.dp(context, if (landscape) 4 else 6)
        val sectionGap = LauncherTheme.dp(context, if (landscape) 8 else 16)
        val keypadTop = LauncherTheme.dp(context, if (landscape) 12 else 24)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(padH, padV, padH, padV)
        root.setBackgroundResource(R.drawable.launcher_dialog_bg)

        // 标题
        val title = TextView(context)
        title.setText(if (mode == MODE_SET) R.string.game_password_set_title else R.string.game_password_enter_title)
        title.gravity = Gravity.CENTER
        title.setTextColor(LauncherTheme.text(context))
        title.setTextSize(16f)
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        // 提示文字（初始为空，仅用于动态消息：确认提示、错误提示）
        val hint = TextView(context)
        hint.setText("")
        hint.gravity = Gravity.CENTER
        hint.setTextColor(LauncherTheme.textMuted(context))
        hint.setTextSize(11f)
        val hintLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hintLp.topMargin = LauncherTheme.dp(context, if (landscape) 8 else 12)
        root.addView(hint, hintLp)

        // 密码圆点指示器
        val dots = arrayOfNulls<View>(PASSWORD_LENGTH)
        val dotsRow = LinearLayout(context)
        dotsRow.orientation = LinearLayout.HORIZONTAL
        dotsRow.gravity = Gravity.CENTER
        val dotsRowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        dotsRowLp.topMargin = sectionGap
        val dotSize = LauncherTheme.dp(context, 12)
        val dotSpacing = LauncherTheme.dp(context, 10)
        for (i in 0 until PASSWORD_LENGTH) {
            val dot = View(context)
            val dotBg = GradientDrawable()
            dotBg.shape = GradientDrawable.OVAL
            dotBg.setColor(LauncherTheme.bg(context))
            dot.background = dotBg
            val dotLp = LinearLayout.LayoutParams(dotSize, dotSize)
            if (i > 0) dotLp.leftMargin = dotSpacing
            dotsRow.addView(dot, dotLp)
            dots[i] = dot
        }
        root.addView(dotsRow, dotsRowLp)

        // 九宫格数字键盘
        val input = StringBuilder()
        var firstInputDone = false
        var firstInput = ""

        val keypad = LinearLayout(context)
        keypad.orientation = LinearLayout.VERTICAL
        keypad.gravity = Gravity.CENTER_HORIZONTAL
        val keypadLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        keypadLp.topMargin = keypadTop

        val keys = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del")

        /** 处理一次完整输入：设置模式两次确认、验证模式比对哈希。状态经闭包变量读写保持语义一致。 */
        fun handleComplete(context: Context, dialog: Dialog, mode: Int, entered: String,
                           hashedPassword: String?, hint: TextView,
                           setListener: OnPasswordSetListener?, verifySuccess: Runnable?) {
            if (mode == MODE_SET) {
                if (!firstInputDone) {
                    firstInput = entered
                    firstInputDone = true
                    hint.setText(R.string.game_password_confirm_again)
                } else {
                    if (entered == firstInput) {
                        try {
                            val hashed = hash(entered)
                            dialog.dismiss()
                            setListener?.onPasswordSet(hashed)
                        } catch (e: IllegalStateException) {
                            hint.setText(R.string.game_password_encrypt_save_failed)
                            shakeError(hint)
                            Toast.makeText(context, R.string.game_password_encrypt_failed, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        firstInputDone = false
                        firstInput = ""
                        hint.setText(R.string.game_password_mismatch)
                        shakeError(hint)
                    }
                }
            } else {
                try {
                    val hashed = hash(entered)
                    if (hashed == hashedPassword) {
                        dialog.dismiss()
                        verifySuccess?.run()
                    } else {
                        hint.setText(R.string.game_password_wrong_retry)
                        shakeError(hint)
                        Toast.makeText(context, R.string.game_password_wrong, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: IllegalStateException) {
                    hint.setText(R.string.game_password_encrypt_verify_failed)
                    shakeError(hint)
                    Toast.makeText(context, R.string.game_password_encrypt_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        for (row in 0 until 4) {
            val rowLayout = LinearLayout(context)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            rowLayout.gravity = Gravity.CENTER
            for (col in 0 until 3) {
                val idx = row * 3 + col
                val key = keys[idx]
                if (key.isEmpty()) {
                    val spacer = View(context)
                    val spacerLp = LinearLayout.LayoutParams(btnSize, btnSize)
                    if (col > 0) spacerLp.leftMargin = btnSpacing
                    rowLayout.addView(spacer, spacerLp)
                    continue
                }
                val btn = TextView(context)
                btn.setText(if (key == "del") "X" else key)
                btn.gravity = Gravity.CENTER
                btn.setTextSize(20f)
                btn.setTypeface(null, Typeface.NORMAL)
                btn.setTextColor(LauncherTheme.primary(context))
                val btnLp = LinearLayout.LayoutParams(btnSize, btnSize)
                if (col > 0) btnLp.leftMargin = btnSpacing
                rowLayout.addView(btn, btnLp)

                btn.setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (key == "del") {
                        if (input.isNotEmpty()) {
                            input.deleteCharAt(input.length - 1)
                        }
                    } else {
                        if (input.length < PASSWORD_LENGTH) {
                            input.append(key)
                        }
                    }
                    updateDots(dots, input.length, context)

                    if (input.length == PASSWORD_LENGTH) {
                        val entered = input.toString()
                        input.setLength(0)
                        // 延迟清空圆点，让用户看到最后一个点亮起
                        dots[0]?.postDelayed({
                            if (!dialog.isShowing) return@postDelayed
                            updateDots(dots, 0, context)
                            handleComplete(context, dialog, mode, entered, hashedPassword, hint, setListener, verifySuccess)
                        }, 100)
                    }
                }
            }
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (row > 0) rowLp.topMargin = btnSpacing
            keypad.addView(rowLayout, rowLp)
        }
        root.addView(keypad, keypadLp)

        // 取消按钮
        val cancelBtn = TextView(context)
        cancelBtn.setText(R.string.game_common_cancel)
        cancelBtn.gravity = Gravity.CENTER
        cancelBtn.setTextSize(13f)
        cancelBtn.setTypeface(null, Typeface.BOLD)
        cancelBtn.setTextColor(LauncherTheme.primary(context))
        val cancelLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(context, 38))
        cancelLp.topMargin = LauncherTheme.dp(context, if (landscape) 12 else 20)
        cancelBtn.background = LauncherTheme.cancelChip(context)
        cancelBtn.setOnClickListener { dialog.dismiss() }
        root.addView(cancelBtn, cancelLp)

        val scroll = ScrollView(context)
        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val dialogHeight: Int
            if (landscape) {
                val screenHeight = context.resources.displayMetrics.heightPixels
                dialogHeight = (screenHeight * 0.9f).toInt()
            } else {
                dialogHeight = WindowManager.LayoutParams.WRAP_CONTENT
            }
            window.setLayout(LauncherDialogFactory.dialogWidthPx(context, 280), dialogHeight)
        }
    }

    private fun updateDots(dots: Array<View?>, count: Int, context: Context) {
        val activeColor = LauncherTheme.primary(context)
        val inactiveColor = LauncherTheme.bg(context)
        for (i in dots.indices) {
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(if (i < count) activeColor else inactiveColor)
            dots[i]?.background = bg
        }
    }

    private fun shakeError(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
