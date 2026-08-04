package com.apps.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.apps.LauncherPreferences
import com.core.util.SafeImageLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 头像文件与偏好持久化（com_apps_refactor_plan.md §5.2 项 1）。
 * 从 LauncherHomeFragment.copyAvatarToInternal 下沉，供主页/个人页/平板片段复用；
 * 头像偏好键与文件名单源于此。
 */
object LauncherAvatarPersistence {
    const val FILE_NAME = "launcher_avatar.jpg"

    /** 主页头像偏好键（主源 com.core.CorePreferences.KEY_PROFILE_AVATAR，const val 字面量副本）。 */
    const val KEY_PROFILE_AVATAR = "profile_avatar"
    const val KEY_CUSTOM_AVATAR = "custom_avatar_uri"

    /**
     * 复制头像到内部存储（原子替换 + fd.sync）并持久化两个偏好键
     * （APP_PREFS 的 profile_avatar + PROFILE_PREFS 的 custom_avatar_uri），随后失效图片缓存。
     * 返回持久化后的 file:// URI；失败返回 null（由调用方提示用户）。
     */
    @JvmStatic
    fun copyAvatarToInternal(context: Context, sourceUri: Uri): String? {
        val app = context.applicationContext
        val outFile = File(app.filesDir, FILE_NAME)
        val savedUri = Uri.fromFile(outFile).toString()
        var tempFile: File? = null
        return try {
            val pendingFile = File.createTempFile("launcher_avatar_", ".tmp", app.filesDir)
            tempFile = pendingFile
            val input = app.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Unable to open avatar source")
            input.use {
                FileOutputStream(pendingFile).use { out ->
                    val buffer = ByteArray(8192)
                    var n = it.read(buffer)
                    while (n > 0) {
                        out.write(buffer, 0, n)
                        n = it.read(buffer)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            // 临时文件与目标位于同一目录；原子替换失败时旧头像保持不变。
            Files.move(
                pendingFile.toPath(),
                outFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            val homeCommitted = app.getSharedPreferences(LauncherPreferences.APP_PREFS, 0)
                .edit().putString(KEY_PROFILE_AVATAR, savedUri).commit()
            if (homeCommitted) {
                val profileCommitted = app.getSharedPreferences(LauncherPreferences.PROFILE_PREFS, 0)
                    .edit().putString(KEY_CUSTOM_AVATAR, savedUri).commit()
                SafeImageLoader.invalidateUri(savedUri)
                if (!profileCommitted) {
                    Log.w("LauncherAvatarPersistence", "Failed to mirror avatar preference")
                }
            }
            if (homeCommitted) savedUri else null
        } catch (e: Exception) {
            Log.w("LauncherAvatarPersistence", "Failed to persist avatar", e)
            null
        } finally {
            tempFile?.let { pending ->
                if (pending.exists() && !pending.delete()) {
                    Log.w("LauncherAvatarPersistence", "Failed to delete temporary avatar")
                }
            }
        }
    }
}
