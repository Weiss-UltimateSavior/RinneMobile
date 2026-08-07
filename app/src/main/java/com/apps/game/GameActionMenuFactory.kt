package com.apps.game

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherMotion
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.Game
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import com.core.util.TimeFormatUtil
import java.util.function.Consumer

/**
 * 抽取自 [LauncherLibraryFragment] 与 `PadManageFragment` 的公共游戏动作菜单与
 * 子对话框逻辑。两个 Fragment 通过 [SubDialogFactory] 注入各自偏好的单选对话框实现，
 * 通过 [ActionMenuConfig] 控制动作菜单可选项，通过 [ActionMenuCallbacks] 把菜单
 * 事件回传给 Fragment 自身实现。
 */
object GameActionMenuFactory {

    /** 单选对话框工厂，由 Fragment 注入（Library 用 LauncherDialogFactory，Pad 用 PadDialogFactory）。 */
    fun interface SubDialogFactory {
        fun showSingleChoice(
            ctx: Context, title: String?, labels: Array<CharSequence>,
            checkedIndex: Int, onChoice: Consumer<Int>
        )
    }

    /** 游戏数据更新回调，工厂异步写入数据库后把最新 Game 回传给 Fragment 就地刷新。 */
    fun interface GameUpdateCallback {
        fun onGameUpdated(updated: Game)
    }

    /** 动作菜单事件回调，由 Fragment 实现。 */
    interface ActionMenuCallbacks {
        fun onShowGameDetail(game: Game)
        fun onEditGame(game: Game)
        fun onShowPlayStatus(game: Game)
        fun onEditPlayTime(game: Game)
        fun onToggleFavorite(game: Game)
        fun onTogglePassword(game: Game)
        fun onShowMoreOptions(game: Game)
    }

    /** 动作菜单可选项配置。 */
    class ActionMenuConfig {
        @JvmField var includeEditAction: Boolean = true
        @JvmField var includeEditPlayTimeAction: Boolean = false
        @JvmField var includeFavoriteAction: Boolean = true
        @JvmField var includePasswordAction: Boolean = true
        @JvmField var dialogWidthDp: Int = 252
    }

    // ===== 静态 UI 元素 =====

    /** 创建透明背景的 AlertDialog 并应用 Launcher 动画。 */
    @JvmStatic
    fun createLauncherDialog(ctx: Context): AlertDialog {
        val dialog = AlertDialog.Builder(ctx).create()
        dialog.show()
        LauncherMotion.applyDialogMotion(dialog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    /** 创建竖向 LinearLayout，统一使用 launcher_dialog_bg 作为背景。 */
    @JvmStatic
    fun createDialogRoot(ctx: Context): LinearLayout {
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(LauncherTheme.dp(ctx, 22), LauncherTheme.dp(ctx, 20), LauncherTheme.dp(ctx, 22), LauncherTheme.dp(ctx, 16))
        root.setBackgroundResource(R.drawable.launcher_dialog_bg)
        return root
    }

    /** 创建居中加粗的标题 TextView。 */
    @JvmStatic
    fun createDialogTitle(ctx: Context, text: String?): TextView {
        val title = TextView(ctx)
        title.text = text
        title.gravity = Gravity.CENTER
        title.setSingleLine(true)
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setTextColor(LauncherTheme.text(ctx))
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        return title
    }

    /** 创建主/次操作按钮，点击后先关闭对话框再执行 action。 */
    @JvmStatic
    fun createDialogButton(
        ctx: Context, text: String?, primary: Boolean,
        action: Runnable, dialog: AlertDialog
    ): TextView {
        val btn = TextView(ctx)
        btn.text = text
        btn.gravity = Gravity.CENTER
        btn.textSize = 13f
        btn.setTypeface(null, Typeface.BOLD)
        if (primary) {
            LauncherTheme.primaryButton(btn)
        } else {
            LauncherTheme.secondaryButton(btn)
        }
        btn.setOnClickListener {
            dialog.dismiss()
            action.run()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(ctx, 38)
        )
        lp.setMargins(0, LauncherTheme.dp(ctx, 9), 0, 0)
        btn.layoutParams = lp
        return btn
    }

    /** 创建圆角 chip 风格的取消按钮。 */
    @JvmStatic
    fun createDialogCancelButton(ctx: Context, dialog: AlertDialog): TextView {
        val cancel = TextView(ctx)
        cancel.setText(R.string.game_common_cancel)
        cancel.gravity = Gravity.CENTER
        cancel.setTextColor(LauncherTheme.primary(ctx))
        cancel.textSize = 13f
        cancel.setTypeface(null, Typeface.BOLD)
        cancel.background = LauncherTheme.cancelChip(ctx)
        cancel.setOnClickListener { dialog.dismiss() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(ctx, 36)
        )
        lp.setMargins(0, LauncherTheme.dp(ctx, 9), 0, 0)
        cancel.layoutParams = lp
        return cancel
    }

    /** 设置对话框内容与宽度（widthDp 为 dp 值，内部转 px 并做屏幕宽度兜底）。 */
    @JvmStatic
    fun setDialogContent(dialog: AlertDialog, content: View, widthDp: Int) {
        val window = dialog.window ?: return
        window.setContentView(content)
        window.setLayout(
            LauncherDialogFactory.dialogWidthPx(content.context, widthDp),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * 展示「已有游戏正在运行」提示弹窗。原实现位于 com.core 桥接层（平台 AlertDialog），
     * 迁移到 com.apps 侧走主题工厂，保持弹窗样式与 Launcher 一致（§9.5 架构治理）。
     * 需要 Activity context；非 Activity context 无法持有弹窗窗口，按打开失败静默处理。
     */
    @JvmStatic
    fun showActiveGameInfo(context: Context?, activeGameTitle: String?) {
        if (context == null) return
        val title = activeGameTitle?.trim().takeUnless { it.isNullOrEmpty() }
            ?: context.getString(R.string.core_current_game)
        try {
            LauncherDialogRouter.showInfo(
                context,
                context.getString(R.string.core_active_game_title),
                context.getString(R.string.core_active_game_dialog_message, title)
            )
        } catch (_: Exception) {
            // A non-Activity context cannot own a dialog window. Callers still receive the message.
        }
    }

    // ===== 动作菜单 =====

    /** 构造完整的游戏长按动作菜单，可选项由 config 控制，事件由 callbacks 回传。 */
    @JvmStatic
    fun showGameActionMenu(
        fragment: Fragment, game: Game?,
        config: ActionMenuConfig, callbacks: ActionMenuCallbacks
    ) {
        if (game == null) return
        val ctx = fragment.requireContext()
        val dialog = createLauncherDialog(ctx)
        val root = createDialogRoot(ctx)
        root.addView(createDialogTitle(ctx, GameMetadataFormatter.safeTitle(ctx, game)))

        addActionOption(ctx, root, ctx.getString(R.string.game_action_details), dialog) { callbacks.onShowGameDetail(game) }
        if (config.includeEditAction) {
            addActionOption(ctx, root, ctx.getString(R.string.game_action_edit), dialog) { callbacks.onEditGame(game) }
        }
        addActionOption(ctx, root, ctx.getString(R.string.game_action_status), dialog) { callbacks.onShowPlayStatus(game) }
        if (config.includeEditPlayTimeAction) {
            addActionOption(ctx, root, ctx.getString(R.string.game_action_edit_duration), dialog) { callbacks.onEditPlayTime(game) }
        }
        if (config.includeFavoriteAction) {
            val favoriteLabel = ctx.getString(if (game.favorite)
                R.string.game_action_favorite_remove else R.string.game_action_favorite_add)
            addActionOption(ctx, root, favoriteLabel, dialog) { callbacks.onToggleFavorite(game) }
        }
        if (config.includePasswordAction) {
            val passwordLabel = ctx.getString(if (GamePasswordLock.hasPassword(game))
                R.string.game_action_password_remove else R.string.game_action_password_lock)
            addActionOption(ctx, root, passwordLabel, dialog) { callbacks.onTogglePassword(game) }
        }
        addActionOption(ctx, root, ctx.getString(R.string.game_action_more), dialog) { callbacks.onShowMoreOptions(game) }

        root.addView(createDialogCancelButton(ctx, dialog))
        setDialogContent(dialog, root, config.dialogWidthDp)
    }

    /** 内部辅助：往 root 中追加一个菜单选项 TextView。 */
    private fun addActionOption(
        ctx: Context, root: LinearLayout, label: String?,
        dialog: AlertDialog, action: Runnable
    ) {
        val option = TextView(ctx)
        option.text = label
        option.gravity = Gravity.CENTER
        option.setSingleLine(true)
        option.textSize = 13f
        option.setTypeface(null, Typeface.BOLD)
        LauncherTheme.menuItem(option)
        option.setOnClickListener {
            dialog.dismiss()
            action.run()
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LauncherTheme.dp(ctx, 36)
        )
        lp.setMargins(0, LauncherTheme.dp(ctx, 11), 0, 0)
        root.addView(option, lp)
    }

    // ===== 子对话框 =====

    /** 显示游玩状态单选对话框，单选实现由 SubDialogFactory 注入。 */
    @JvmStatic
    fun showPlayStatusDialog(
        fragment: Fragment, game: Game?,
        subDialogFactory: SubDialogFactory,
        callback: GameUpdateCallback
    ) {
        if (game == null) return
        val ctx = fragment.requireContext()
        val labels: Array<CharSequence> = arrayOf(
            ctx.getString(R.string.game_status_unplayed),
            ctx.getString(R.string.game_status_playing),
            ctx.getString(R.string.game_status_completed)
        )
        val values = arrayOf("unplayed", "playing", "completed")
        var checkedIndex = -1
        for (i in values.indices) {
            if (values[i] == game.playStatus) {
                checkedIndex = i
                break
            }
        }
        subDialogFactory.showSingleChoice(
            ctx,
            ctx.getString(R.string.game_action_set_status),
            labels,
            checkedIndex
        ) { index -> updateGameStatus(fragment, game, values[index], callback) }
    }

    /** 异步写入游戏状态，完成后通过 callback 回传最新 Game。 */
    @JvmStatic
    fun updateGameStatus(
        fragment: Fragment, game: Game, status: String,
        callback: GameUpdateCallback
    ) {
        val appContext = fragment.requireContext().applicationContext
        AppExecutors.runOnSingle {
            var updated: Game? = null
            try {
                val latest = LauncherRepositoryBridge.findGameById(appContext, game.id)
                if (latest != null) {
                    latest.playStatus = status
                    if (LauncherRepositoryBridge.updateGame(appContext, latest) > 0) {
                        updated = latest
                    }
                }
            } catch (e: Exception) {
                Log.w("GameActionMenuFactory", "Failed to update game status", e)
            }
            val result = updated
            RxMainScheduler.post {
                if (!fragment.isAdded || fragment.view == null) return@post
                if (result != null) callback.onGameUpdated(result)
            }
        }
    }

    /** 显示游戏详情对话框（状态/引擎/时长/最近游玩/模拟器/路径）。 */
    @JvmStatic
    fun showGameDetailDialog(fragment: Fragment, game: Game?) {
        if (game == null) return
        val ctx = fragment.requireContext()
        val dialog = createLauncherDialog(ctx)
        val root = createDialogRoot(ctx)
        root.addView(createDialogTitle(ctx, GameMetadataFormatter.safeTitle(ctx, game)))

        val info = TextView(ctx)
        val sb = StringBuilder()
        sb.append(ctx.getString(
            R.string.game_detail_format,
            GameMetadataFormatter.playStatusText(ctx, game.playStatus),
            GameMetadataFormatter.engineText(ctx, game.engine),
            TimeFormatUtil.playTime(game.totalPlayTime),
            if (game.lastPlayedAt > 0) TimeFormatUtil.date(game.lastPlayedAt)
            else ctx.getString(R.string.game_status_never_played)
        ))
        val emulatorPackage = game.emulatorPackage
        if (emulatorPackage != null && emulatorPackage.trim { it <= ' ' }.isNotEmpty()) {
            sb.append("\n").append(ctx.getString(R.string.game_detail_emulator, emulatorPackage))
        }
        sb.append("\n\n").append(ctx.getString(
            R.string.game_detail_path, if (game.rootUri == null) "" else Uri.decode(game.rootUri)))
        info.text = sb.toString()
        info.setTextColor(LauncherTheme.text(ctx))
        info.textSize = 12f
        info.setLineSpacing(LauncherTheme.dp(ctx, 4).toFloat(), 1f)
        info.maxLines = 14
        info.isVerticalScrollBarEnabled = true
        info.movementMethod = ScrollingMovementMethod.getInstance()
        val infoLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        infoLp.setMargins(0, LauncherTheme.dp(ctx, 13), 0, 0)
        root.addView(info, infoLp)

        root.addView(createDialogCancelButton(ctx, dialog))
        setDialogContent(dialog, root, 288)
    }

    /** 显示修改游玩时长对话框（实现已拆分至 [EditPlayTimeDialog]，见 §5.1）。 */
    @JvmStatic
    fun showEditPlayTimeDialog(
        fragment: Fragment, game: Game?,
        callback: GameUpdateCallback
    ) {
        EditPlayTimeDialog.show(fragment, game, callback)
    }
}
