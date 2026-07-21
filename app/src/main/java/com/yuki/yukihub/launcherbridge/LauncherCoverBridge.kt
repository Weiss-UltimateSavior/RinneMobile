package com.yuki.yukihub.launcherbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.metadata.VndbClient
import com.yuki.yukihub.model.Game
import com.yuki.yukihub.util.AppExecutors
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 封面下载桥接：从 VNDB 等来源下载封面并保存到本地。
 */
object LauncherCoverBridge {

    private const val MAX_COVER_RESPONSE_BYTES = 20L * 1024L * 1024L

    @JvmStatic
    fun fetchCoverForGameAsync(context: Context, game: Game?) {
        if (game == null || game.id <= 0) return
        if (game.title.isNullOrBlank()) return
        val appContext = context.applicationContext
        val target = copyGame(game)
        AppExecutors.io().execute {
            try {
                val candidates = VndbClient.searchCandidates(target.title, 1)
                if (candidates.isNullOrEmpty()) return@execute
                val meta = candidates[0]
                if (meta.coverUrl.isNullOrBlank()) return@execute
                val cover = downloadAndSaveCover(appContext, meta.coverUrl, "scan_cover_${target.id}")
                    ?: return@execute
                val repository = GameRepository(appContext)
                val latest = repository.findById(target.id) ?: return@execute
                if (!latest.coverUri.isNullOrBlank()) return@execute
                latest.coverUri = cover
                latest.coverPersistUri = cover
                latest.coverSourceType = 1
                repository.update(latest)
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun downloadCover(context: Context, imageUrl: String, prefix: String): String? =
        downloadAndSaveCover(context, imageUrl, prefix)

    private fun downloadAndSaveCover(context: Context, imageUrl: String?, prefix: String): String? =
        downloadAndSaveCover(context, imageUrl, prefix, 0)

    private fun downloadAndSaveCover(context: Context, imageUrl: String?, prefix: String, redirects: Int): String? {
        if (imageUrl.isNullOrBlank()) return null
        if (redirects > 5) return null
        var inputStream: InputStream? = null
        var conn: HttpURLConnection? = null
        var cacheFile: File? = null
        try {
            val dir = File(context.filesDir, "covers_remote")
            if (!dir.exists()) dir.mkdirs()
            val name = "${prefix}_${Math.abs(imageUrl.hashCode())}.jpg"
            cacheFile = File(dir, name)
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return Uri.fromFile(cacheFile).toString()
            }
            val url = URL(imageUrl.trim())
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Referer", "https://vndb.org/")
            conn.setRequestProperty("Cookie", "vndb_img=1; vndb_samesite=1")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_SEE_OTHER) {
                val next = conn.getHeaderField("Location")
                if (!next.isNullOrEmpty()) return downloadAndSaveCover(context, next, prefix, redirects + 1)
                return null
            }
            if (code !in 200..299) return null
            val declaredLength = conn.contentLengthLong
            if (declaredLength > MAX_COVER_RESPONSE_BYTES) return null
            inputStream = conn.inputStream
            val buffer = ByteArray(8192)
            var len: Int
            var total = 0
            FileOutputStream(cacheFile).use { fos ->
                while (inputStream!!.read(buffer).also { len = it } != -1) {
                    total += len
                    if (total > MAX_COVER_RESPONSE_BYTES) throw java.io.IOException("cover download too large")
                    fos.write(buffer, 0, len)
                }
            }
            inputStream!!.close()
            inputStream = null
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
            if (opts.outWidth <= 0) {
                cacheFile.delete()
                return null
            }
            val maxPx = 720
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1440) sampleSize *= 2
            val decodeOpts = BitmapFactory.Options()
            decodeOpts.inSampleSize = sampleSize
            var bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath, decodeOpts)
                ?: run {
                    cacheFile.delete()
                    return null
                }
            val scale = minOf(1f, maxPx.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (scale < 1f) {
                val nw = Math.round(bitmap.width * scale)
                val nh = Math.round(bitmap.height * scale)
                val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
                bitmap.recycle()
                bitmap = scaled
                FileOutputStream(cacheFile).use { fos2 ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos2)
                }
            }
            bitmap.recycle()
            return Uri.fromFile(cacheFile).toString()
        } catch (_: Throwable) {
            cacheFile?.let { try { it.delete() } catch (_: Throwable) {} }
            return null
        } finally {
            inputStream?.let { try { it.close() } catch (_: Throwable) {} }
            conn?.disconnect()
        }
    }

    private fun copyGame(src: Game): Game {
        val g = Game()
        g.id = src.id
        g.title = src.title
        g.rootUri = src.rootUri
        g.engine = src.engine
        return g
    }
}
