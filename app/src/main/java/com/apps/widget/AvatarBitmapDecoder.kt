package com.apps.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * 头像裁剪原图解码器：先 inJustDecodeBounds 量尺寸，再按目标边长降采样解码，避免 OOM。
 *
 * 由 [AvatarCropView] 在 IO 线程调用；[OutOfMemoryError] 始终重抛，
 * 其余异常仅记录日志并返回 null（调用方回退）。
 */
internal object AvatarBitmapDecoder {
    private const val TAG = "AvatarBitmapDecoder"

    fun decode(context: Context, uri: Uri, target: Int): Bitmap? {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        try {
            open(context, uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (e: IOException) {
            Log.w(TAG, "avatar-probe-decode-failed", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "avatar-probe-decode-failed", e)
        }
        opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, target)
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        return try {
            open(context, uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (e: IOException) {
            Log.w(TAG, "avatar-decode-failed", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "avatar-decode-failed", e)
            null
        }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    private fun calculateSampleSize(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        while (w / sample > target || h / sample > target) sample *= 2
        return sample
    }
}
