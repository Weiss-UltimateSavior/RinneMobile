package com.apps.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import com.yuki.yukihub.util.AppExecutors
import com.yuki.yukihub.util.RxMainScheduler
import kotlin.math.max

/** Async cover decoder with a bounded in-memory bitmap cache. */
object LauncherCoverLoader {
    private const val TARGET_WIDTH = 480
    private const val TARGET_HEIGHT = 432
    private val cache = object : LruCache<String, Bitmap>(max(2 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 8).toInt())) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    interface Callback { fun onLoaded(success: Boolean) }

    @JvmStatic
    fun loadInto(imageView: ImageView?, uriText: String?, callback: Callback?) {
        imageView ?: return
        val key = uriText?.trim().orEmpty()
        if (key == imageView.tag as? String) return
        imageView.tag = key
        imageView.setImageDrawable(null)
        if (key.isEmpty()) { callback?.onLoaded(false); return }
        cache[key]?.let { imageView.setImageBitmap(it); callback?.onLoaded(true); return }
        val context = imageView.context.applicationContext
        AppExecutors.runOnIo {
            val bitmap = decodeSampled(context, key)
            bitmap?.let { cache.put(key, it) }
            RxMainScheduler.post {
                if (key != imageView.tag) return@post
                bitmap?.let(imageView::setImageBitmap)
                callback?.onLoaded(bitmap != null)
            }
        }
    }

    @JvmStatic
    fun clear(imageView: ImageView?) {
        imageView ?: return
        imageView.tag = null
        imageView.setImageDrawable(null)
    }

    private fun decodeSampled(context: Context, uriText: String): Bitmap? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(uriText))?.use { BitmapFactory.decodeStream(it, null, options) }
        options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        context.contentResolver.openInputStream(Uri.parse(uriText))?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: Throwable) { null }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > TARGET_WIDTH || height / sample > TARGET_HEIGHT) sample *= 2
        return max(1, sample)
    }
}
