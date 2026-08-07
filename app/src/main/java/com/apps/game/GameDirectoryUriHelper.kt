package com.apps.game

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.core.R
import com.core.util.DevLogger

/**
 * 编辑游戏页 SAF 目录 URI 的持久化授权与显示（阶段 142 从 [LauncherGameEditFragment] 抽离）。
 */
internal object GameDirectoryUriHelper {
    private const val TAG = "LauncherGameEdit"

    /** 持久化 URI 授权。返回 true 表示 RW 授权成功，false 表示降级为只读或彻底失败。 */
    fun persistUriPermission(context: Context, uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (first: SecurityException) {
            DevLogger.w(TAG, "takePersistableUriPermission(RW) failed, retry RO", first)
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                false
            } catch (second: SecurityException) {
                DevLogger.w(TAG, "takePersistableUriPermission(RO) failed", second)
                false
            }
        }
    }

    /** 将 tree URI 转为可读目录名；混合/纯 document URI 或解析失败时回退原始字符串。 */
    fun displayDirectoryUri(context: Context, uri: Uri?): String {
        if (uri == null) return context.getString(R.string.game_directory_not_selected)
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            if (!documentId.isNullOrBlank()) Uri.decode(documentId) else uri.toString()
        } catch (error: Exception) {
            if (error !is IllegalArgumentException && error !is SecurityException) throw error
            DevLogger.w(TAG, "Failed to display tree URI; using raw URI", error)
            uri.toString()
        }
    }
}
