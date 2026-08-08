package com.core.launcher

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.Inflater

/** Minimal, bounded XP3 index reader used only to identify executable-embedded startup archives. */
internal object EmbeddedXp3Probe {
    private val SIGNATURE = byteArrayOf(
        0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, 0x8b.toByte(), 0x67, 0x01,
    )
    private val UTF16_LE: Charset = Charset.forName("UTF-16LE")
    private const val MAX_EXECUTABLE_SIZE = 256L * 1024 * 1024
    private const val MAX_INDEX_SIZE = 64L * 1024 * 1024
    private const val SCAN_BUFFER_SIZE = 64 * 1024

    fun containsStartupScript(file: File): Boolean {
        if (!file.isFile || file.length() < SIGNATURE.size + 9 || file.length() > MAX_EXECUTABLE_SIZE) {
            return false
        }
        return try {
            RandomAccessFile(file, "r").use { input ->
                findSignatureOffsets(input).any { archiveOffset ->
                    runCatching { indexContainsStartup(input, archiveOffset) }.getOrDefault(false)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findSignatureOffsets(input: RandomAccessFile): List<Long> {
        val result = ArrayList<Long>()
        val overlap = SIGNATURE.size - 1
        val buffer = ByteArray(SCAN_BUFFER_SIZE + overlap)
        var carry = 0
        var absolute = 0L
        input.seek(0)
        while (absolute < input.length()) {
            val count = input.read(buffer, carry, SCAN_BUFFER_SIZE)
            if (count <= 0) break
            val total = carry + count
            var index = 0
            while (index <= total - SIGNATURE.size) {
                if (matches(buffer, index, SIGNATURE)) {
                    result.add(absolute - carry + index)
                }
                index++
            }
            carry = minOf(overlap, total)
            buffer.copyInto(buffer, 0, total - carry, total)
            absolute += count
        }
        return result
    }

    private fun indexContainsStartup(input: RandomAccessFile, archiveOffset: Long): Boolean {
        val indexOffset = readUnsignedLong(input, archiveOffset + SIGNATURE.size)
        if (indexOffset < SIGNATURE.size || indexOffset > input.length() - archiveOffset - 9) return false
        var blockOffset = archiveOffset + indexOffset
        val combined = ByteArrayOutputStream()
        do {
            if (blockOffset < 0 || blockOffset >= input.length()) return false
            input.seek(blockOffset)
            val flag = input.readUnsignedByte()
            val compressed = flag and 0x07
            val continues = flag and 0x80 != 0
            val storedSize: Long
            val originalSize: Long
            when (compressed) {
                0 -> {
                    storedSize = readUnsignedLong(input)
                    originalSize = storedSize
                }
                1 -> {
                    storedSize = readUnsignedLong(input)
                    originalSize = readUnsignedLong(input)
                }
                else -> return false
            }
            if (
                storedSize < 0 || originalSize < 0 || storedSize > MAX_INDEX_SIZE ||
                originalSize > MAX_INDEX_SIZE || input.filePointer > input.length() - storedSize ||
                combined.size().toLong() + originalSize > MAX_INDEX_SIZE
            ) return false
            val stored = ByteArray(storedSize.toInt())
            input.readFully(stored)
            val decoded = if (compressed == 0) stored else inflate(stored, originalSize.toInt()) ?: return false
            combined.write(decoded)
            blockOffset = input.filePointer
        } while (continues)
        return parseIndexForStartup(combined.toByteArray())
    }

    private fun parseIndexForStartup(index: ByteArray): Boolean {
        var position = 0
        while (position <= index.size - 12) {
            val chunkSize = littleEndianLong(index, position + 4)
            if (chunkSize < 0 || chunkSize > index.size - position - 12) return false
            val end = position + 12 + chunkSize.toInt()
            if (matchesAscii(index, position, "File") && fileChunkContainsStartup(index, position + 12, end)) {
                return true
            }
            position = end
        }
        return false
    }

    private fun fileChunkContainsStartup(data: ByteArray, start: Int, end: Int): Boolean {
        var position = start
        while (position <= end - 12) {
            val chunkSize = littleEndianLong(data, position + 4)
            if (chunkSize < 0 || chunkSize > end - position - 12) return false
            val body = position + 12
            val chunkEnd = body + chunkSize.toInt()
            if (matchesAscii(data, position, "info") && chunkSize >= 22) {
                val nameLength = littleEndianUnsignedShort(data, body + 20)
                val byteLength = nameLength * 2
                if (body + 22 + byteLength <= chunkEnd) {
                    val name = String(data, body + 22, byteLength, UTF16_LE)
                        .replace('\\', '/')
                        .trimStart('/')
                        .lowercase(Locale.ROOT)
                    if (name == "startup.tjs") return true
                }
            }
            position = chunkEnd
        }
        return false
    }

    private fun inflate(stored: ByteArray, expectedSize: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(stored)
            val output = ByteArray(expectedSize)
            var position = 0
            while (!inflater.finished() && position < output.size) {
                val count = inflater.inflate(output, position, output.size - position)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                } else {
                    position += count
                }
            }
            if (!inflater.finished() || position != expectedSize) null else output
        } finally {
            inflater.end()
        }
    }

    private fun readUnsignedLong(input: RandomAccessFile, offset: Long): Long {
        if (offset < 0 || offset > input.length() - 8) return -1
        input.seek(offset)
        return readUnsignedLong(input)
    }

    private fun readUnsignedLong(input: RandomAccessFile): Long {
        val bytes = ByteArray(8)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long.takeIf { it >= 0 } ?: -1
    }

    private fun littleEndianLong(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun littleEndianUnsignedShort(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun matches(data: ByteArray, offset: Int, expected: ByteArray): Boolean {
        for (index in expected.indices) if (data[offset + index] != expected[index]) return false
        return true
    }

    private fun matchesAscii(data: ByteArray, offset: Int, expected: String): Boolean {
        for (index in expected.indices) if (data[offset + index].toInt() != expected[index].code) return false
        return true
    }
}
