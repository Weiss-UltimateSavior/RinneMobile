package com.core.util

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Bitmap 源文件读取字节上限（32MB），与 LauncherCoverBridge 的 20MB 预检为各自独立的限额
 * （本包装供 decodeStream 等无法预读整体尺寸的路径使用），用于防止超大图片文件导致 OOM 或 IO 放大（§8:305 字节上限要求）。
 */
internal const val MAX_BITMAP_SOURCE_BYTES: Long = 32L * 1024L * 1024L

/**
 * 带字节上限的输入流包装：累计读取/跳过字节数超过 [maxBytes] 时抛 [IOException]。
 *
 * 供 [android.graphics.BitmapFactory].decodeStream 等内部消费流的路径使用——
 * 这类路径无法预读整体尺寸，只能通过包装流在消费侧强制字节上限。
 * 仅限同模块 Kotlin 调用方使用。
 * 未覆盖 mark/reset：委托给底层流，因 count 单调累计，重复读取只会更早触达上限，不存在绕过风险。
 */
internal class BoundedInputStream(
    source: InputStream,
    private val maxBytes: Long
) : FilterInputStream(source) {

    private var count: Long = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) addAndCheck(1L)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) addAndCheck(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) addAndCheck(skipped)
        return skipped
    }

    override fun available(): Int {
        val remaining = (maxBytes - count).coerceAtLeast(0L)
        return minOf(super.available().toLong(), remaining).toInt()
    }

    private fun addAndCheck(bytes: Long) {
        count += bytes
        if (count > maxBytes) {
            throw IOException("input stream exceeds $maxBytes bytes limit")
        }
    }
}
