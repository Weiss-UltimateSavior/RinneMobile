package com.apps.game

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.apps.LauncherActivity
import com.yuki.yukihub.R
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.launcherbridge.LauncherGameLaunchBridge
import com.yuki.yukihub.model.Game
import com.yuki.yukihub.util.AppExecutors
import com.yuki.yukihub.util.RxMainScheduler

/** Creates and handles Android pinned shortcuts for individual library games. */
object PinnedGameShortcut {
    private const val SHORTCUT_ID_PREFIX = "game_"
    private const val MAX_ICON_SIZE_PX = 192

    interface LaunchCallback {
        fun onResult(success: Boolean, message: String?)
    }

    @JvmStatic
    fun requestPinShortcut(context: Context, game: Game?) {
        if (game == null || game.id <= 0L) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(context, "当前 Android 版本不支持添加桌面快捷方式", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            Toast.makeText(context, "当前桌面不支持添加快捷方式", Toast.LENGTH_SHORT).show()
            return
        }
        val title = game.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "未命名游戏"
        val intent = Intent(context, LauncherActivity::class.java)
            .setAction(LauncherActivity.ACTION_LAUNCH_PINNED_GAME)
            .putExtra(LauncherActivity.EXTRA_PINNED_GAME_ID, game.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID_PREFIX + game.id)
            .setShortLabel(title)
            .setLongLabel(title)
            .setIntent(intent)
            .setIcon(shortcutIcon(context, game))
            .build()
        manager.requestPinShortcut(shortcut, null)
        Toast.makeText(context, "请在系统弹窗中确认添加到桌面", Toast.LENGTH_SHORT).show()
    }

    /** Resolves the current game by id before delegating to the shared launch bridge. */
    @JvmStatic
    fun launchPinnedGame(context: Context, gameId: Long, callback: LaunchCallback?) {
        if (gameId <= 0L) {
            callback?.onResult(false, "游戏快捷方式无效")
            return
        }
        val appContext = context.applicationContext
        AppExecutors.runOnIo {
            val game = GameRepository(appContext).findById(gameId)
            val result = LauncherGameLaunchBridge.launch(appContext, game)
            RxMainScheduler.post {
                callback?.onResult(result.success, result.message)
            }
        }
    }

    private fun shortcutIcon(context: Context, game: Game): Icon {
        val source = game.coverPersistUri?.trim().takeUnless { it.isNullOrEmpty() }
            ?: game.coverUri?.trim().takeUnless { it.isNullOrEmpty() }
        val bitmap = source?.let { decodeShortcutBitmap(context, it) }
        return if (bitmap != null) Icon.createWithBitmap(bitmap)
        else Icon.createWithResource(context, R.mipmap.ic_launcher)
    }

    private fun decodeShortcutBitmap(context: Context, source: String): Bitmap? {
        return try {
            val uri = Uri.parse(source)
            val decoded = if (uri.scheme == "file") {
                BitmapFactory.decodeFile(uri.path)
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } ?: return null
            // 桌面图标使用中心裁剪的正方形，避免将竖版或横版封面挤压变形。
            val edge = minOf(decoded.width, decoded.height)
            val square = if (decoded.width == decoded.height) {
                decoded
            } else {
                Bitmap.createBitmap(
                    decoded,
                    (decoded.width - edge) / 2,
                    (decoded.height - edge) / 2,
                    edge,
                    edge
                ).also { decoded.recycle() }
            }
            if (square.width <= MAX_ICON_SIZE_PX) return square
            val scale = MAX_ICON_SIZE_PX.toFloat() / square.width
            val scaled = Bitmap.createScaledBitmap(
                square,
                (square.width * scale).toInt().coerceAtLeast(1),
                (square.height * scale).toInt().coerceAtLeast(1),
                true
            )
            square.recycle()
            scaled
        } catch (_: Throwable) {
            null
        }
    }
}
