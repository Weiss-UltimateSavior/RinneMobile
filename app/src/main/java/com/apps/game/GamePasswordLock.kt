package com.apps.game

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.Game
import com.core.util.AppExecutors

/**
 * 游戏密码锁定工具类。
 * 提供设置/取消/验证密码的统一入口，供 LauncherGameActionController 和三个 Fragment 共用。
 */
object GamePasswordLock {

    /** 检查游戏是否已设置密码 */
    @JvmStatic
    fun hasPassword(game: Game?): Boolean {
        val lock = game?.passwordLock
        return lock != null && lock.trim { it <= ' ' }.isNotEmpty()
    }

    /** 设置密码：弹出九宫格 → 两次确认 → 保存到 DB */
    @JvmStatic
    fun setPassword(fragment: Fragment?, game: Game?, onDone: Runnable?) {
        if (fragment == null || game == null || !fragment.isAdded) return
        val title = safeTitle(game)
        GamePasswordDialog.showSetDialog(fragment.requireContext(), title) { hashedPassword ->
            savePasswordToDb(fragment, game, hashedPassword, "密码已设置", onDone)
        }
    }

    /** 取消密码：验证当前密码后清除 */
    @JvmStatic
    fun clearPassword(fragment: Fragment?, game: Game?, onDone: Runnable?) {
        if (fragment == null || game == null || !fragment.isAdded) return
        val title = safeTitle(game)
        GamePasswordDialog.showVerifyDialog(fragment.requireContext(), title, game.passwordLock) {
            savePasswordToDb(fragment, game, null, "密码已取消", onDone)
        }
    }

    /**
     * 启动前密码验证拦截。
     * 有密码 → 弹验证框 → 验证成功 → onLaunch.run()
     * 无密码 → 直接 onLaunch.run()
     */
    @JvmStatic
    fun interceptLaunch(fragment: Fragment?, game: Game?, onLaunch: Runnable?) {
        if (fragment == null || game == null || !fragment.isAdded) return
        if (hasPassword(game)) {
            GamePasswordDialog.showVerifyDialog(
                fragment.requireContext(), safeTitle(game), game.passwordLock, onLaunch
            )
        } else {
            onLaunch?.run()
        }
    }

    private fun savePasswordToDb(
        fragment: Fragment, game: Game, hashedPassword: String?,
        toastMessage: String, onDone: Runnable?
    ) {
        val app: Context = fragment.requireContext().applicationContext
        AppExecutors.io().execute {
            var success = false
            try {
                val latest = LauncherRepositoryBridge.findGameById(app, game.id)
                if (latest != null) {
                    latest.passwordLock = hashedPassword
                    LauncherRepositoryBridge.updateGame(app, latest)
                    game.passwordLock = hashedPassword
                    success = true
                }
            } catch (ignored: Throwable) {
            }
            val activity: Activity? = fragment.activity
            if (activity == null) return@execute
            activity.runOnUiThread {
                if (!fragment.isAdded) return@runOnUiThread
                Toast.makeText(fragment.requireContext(), toastMessage, Toast.LENGTH_SHORT).show()
                onDone?.run()
            }
        }
    }

    private fun safeTitle(game: Game): String {
        val title = game.title
        return if (title == null || title.trim { it <= ' ' }.isEmpty()) "未命名游戏"
        else title.trim { it <= ' ' }
    }
}
