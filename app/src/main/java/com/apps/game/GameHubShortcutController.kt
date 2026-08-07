package com.apps.game

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.apps.HDModel.LauncherDialogRouter
import com.core.R
import com.core.databinding.ActivityLauncherGameEditBinding
import com.core.launcherbridge.LauncherGameHubShortcutBridge
import com.core.util.AppExecutors
import com.core.util.DevLogger
import rikka.shizuku.Shizuku

/**
 * GameHub 快捷方式导入子流程（阶段 142 从 [LauncherGameEditFragment] 抽离）：
 * Shizuku 权限监听注册/移除、快捷方式列表拉取与选择应用。
 * 生命周期严格由宿主 Fragment attach/detach 包裹（§8 持有 Fragment 的协调类模式）。
 */
internal class GameHubShortcutController(
    private val fragment: Fragment,
    private val isGameHubEngine: () -> Boolean,
    private val bindingProvider: () -> ActivityLauncherGameEditBinding?,
    private val onShortcutApplied: (LauncherGameHubShortcutBridge.Shortcut) -> Unit,
) {
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_GAMEHUB_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                importFromShizuku()
            } else {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.game_shizuku_manual_id,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    fun attach() {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (error: Exception) {
            // Shizuku 未安装/未初始化时注册监听失败可安全忽略（监听器不生效），记录日志。
            DevLogger.w("LauncherGameEdit", "Failed to register Shizuku permission listener", error)
        }
    }

    fun detach() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (error: Exception) {
            // 同上：Shizuku 状态异常时移除监听失败可安全忽略，记录日志。
            DevLogger.w("LauncherGameEdit", "Failed to remove Shizuku permission listener", error)
        }
    }

    fun importFromShizuku() {
        val currentBinding = bindingProvider() ?: return
        if (!isGameHubEngine()) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.game_select_gamehub_first,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.game_shizuku_start_first,
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_GAMEHUB_PERMISSION_REQUEST)
                return
            }
        } catch (error: Exception) {
            // Shizuku 连接失败（未安装/无权限/binder 异常）提示用户后返回；不可预期异常也在
            // 记录日志后按连接失败降级，避免无 Shizuku 环境的崩溃。
            DevLogger.w("LauncherGameEdit", "Failed to connect to Shizuku", error)
            Toast.makeText(
                fragment.requireContext(),
                fragment.getString(
                    R.string.game_shizuku_connect_failed,
                    error.javaClass.simpleName,
                ),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        currentBinding.btnImportGameHubShortcut.isEnabled = false
        currentBinding.btnImportGameHubShortcut.alpha = 0.45f
        currentBinding.btnImportGameHubShortcut.contentDescription =
            fragment.getString(R.string.game_gamehub_reading_shortcuts)
        AppExecutors.runOnIo {
            val items = try {
                LauncherGameHubShortcutBridge.loadShortcuts()
            } catch (error: Exception) {
                // 快捷方式读取失败（Shizuku 超时/输出过大/未安装等）可安全忽略，按空列表提示。
                DevLogger.w("LauncherGameEdit", "Failed to load GameHub shortcuts", error)
                emptyList()
            }
            val b = bindingProvider() ?: return@runOnIo
            b.root.post {
                if (!fragment.isAdded || bindingProvider() == null) return@post
                b.btnImportGameHubShortcut.isEnabled = true
                b.btnImportGameHubShortcut.alpha = 1f
                b.btnImportGameHubShortcut.contentDescription =
                    fragment.getString(R.string.game_gamehub_import_shortcut)
                if (items.isEmpty()) {
                    showGameHubImportUnavailableDialog()
                    return@post
                }
                showGameHubShortcutPicker(items)
            }
        }
    }

    private fun showGameHubShortcutPicker(items: List<LauncherGameHubShortcutBridge.Shortcut>) {
        val labels = Array<CharSequence>(items.size) { index ->
            items[index].displayLabel + "\n" + items[index].localGameId
        }
        LauncherDialogRouter.showActionChoices(
            fragment.requireContext(),
            fragment.getString(R.string.game_gamehub_choose_shortcut),
            labels,
        ) { which -> onShortcutApplied(items[which]) }
    }

    private fun showGameHubImportUnavailableDialog() {
        LauncherDialogRouter.showInfo(
            fragment.requireContext(),
            fragment.getString(R.string.game_gamehub_no_shortcut_title),
            fragment.getString(R.string.game_gamehub_no_shortcut_help),
        )
    }

    companion object {
        private const val SHIZUKU_GAMEHUB_PERMISSION_REQUEST = 62001
    }
}
