package com.apps.widget

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 裁剪结果 JPEG 写入器：将 [output] 以 90% 质量压缩写入 [outFile]，写完后回收独立的裁剪副本。
 *
 * 由 [AvatarCropActivity] 在 IO 线程调用。压缩失败仅记录日志并返回 null，不抛异常；
 * [OutOfMemoryError] 属于不可恢复信号，始终向上传播。返回非 null 表示写入成功并给出结果 Uri 字符串。
 */
internal object AvatarCropOutputWriter {
    private const val TAG = "AvatarCropOutputWriter"

    @JvmStatic
    fun writeAndRecycle(outFile: File, output: Bitmap, source: Bitmap): String? {
        var ok = false
        try {
            FileOutputStream(outFile).use { fos ->
                ok = output.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (e: IOException) {
            Log.w(TAG, "avatar-jpeg-compress-failed", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "avatar-jpeg-compress-failed", e)
        }
        // 仅回收独立副本；若返回的是源 bitmap 本身则交由 AvatarCropView.release() 处理
        if (output !== source && !output.isRecycled) {
            output.recycle()
        }
        return if (ok) Uri.fromFile(outFile).toString() else null
    }
}
