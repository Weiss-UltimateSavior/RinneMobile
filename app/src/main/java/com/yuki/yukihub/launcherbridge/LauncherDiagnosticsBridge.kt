package com.yuki.yukihub.launcherbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.yuki.yukihub.util.DevLogger
import java.io.File

/**
 * 诊断工具桥接：缓存清理、日志开关、日志导出等。
 */
object LauncherDiagnosticsBridge {

    @JvmStatic
    fun cacheSize(context: Context?): Long {
        if (context == null) return 0L
        return fileSize(context.cacheDir) + fileSize(context.externalCacheDir)
    }

    @JvmStatic
    fun clearCache(context: Context?) {
        if (context == null) return
        deleteChildren(context.cacheDir)
        deleteChildren(context.externalCacheDir)
    }

    @JvmStatic
    fun isLogEnabled(): Boolean = DevLogger.isEnabled()

    @JvmStatic
    fun setLogEnabled(context: Context?, enabled: Boolean) {
        if (context == null) return
        DevLogger.setEnabled(context.applicationContext, enabled)
    }

    @JvmStatic
    fun logSize(): Long = DevLogger.getLogSize()

    @JvmStatic
    fun logFile(): File? = DevLogger.getLogFile()

    @JvmStatic
    fun clearLog(): Boolean = DevLogger.clearLog()

    @JvmStatic
    fun exportLog(context: Context?): Boolean {
        if (context == null) return false
        val logFile = logFile() ?: return false
        if (!logFile.exists()) return false
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
            )
            putExtra(Intent.EXTRA_SUBJECT, "YukiHub Logcat")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "导出日志")
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return true
    }

    private fun fileSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { size += fileSize(it) }
        return size
    }

    private fun deleteChildren(dir: File?) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { deleteRecursively(it) }
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }
}
