package com.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 验证 ArtemisPfsUnpacker 的 .pfs 解析（小端 int / SHA-1 密钥 / XOR 解密）与
 * tyranor 语义的解包判定（system.ini/list_windows 必解，movie 媒体解出，其余跳过）。
 */
class ArtemisPfsUnpackerTest {

    @Test
    fun needsBasePatch_missingSystemIniWithPfs_returnsTrue() {
        val dir = tempDir()
        File(dir, "root.pfs").writeBytes(byteArrayOf(0x70, 0x66, 0x31))
        assertTrue(ArtemisPfsUnpacker.needsBasePatch(dir.path))
    }

    @Test
    fun needsBasePatch_withSystemIni_returnsFalse() {
        val dir = tempDir()
        File(dir, "system.ini").writeText("")
        File(dir, "root.pfs").writeBytes(byteArrayOf(0x70, 0x66, 0x31))
        assertFalse(ArtemisPfsUnpacker.needsBasePatch(dir.path))
    }

    @Test
    fun applyBasePatch_unpacksSystemIniAndMovie_skipsPlainData() {
        val dir = tempDir()
        val systemIni = "WIDTH = 1280\nHEIGHT = 720\n".toByteArray(Charsets.UTF_8)
        val movie = "MOVIEMOVIEMOVIE".toByteArray(Charsets.US_ASCII) // >= 8 触发 XOR 解密
        val script = "SKIPPED_SCRIPT".toByteArray(Charsets.US_ASCII)

        val entries = listOf(
            "system.ini" to systemIni,
            "movie/op.mp4" to movie,
            "data/script.tjs" to script,
        )
        File(dir, "root.pfs").writeBytes(buildPfs(entries))

        assertTrue(ArtemisPfsUnpacker.applyBasePatch(dir.path))

        // system.ini 解出且补齐 [ANDROID] 段
        val patched = File(dir, "system.ini").readText(Charsets.UTF_8)
        assertTrue("system.ini content=[$patched]", patched.contains("WIDTH = 1280"))
        assertTrue(patched.contains("[ANDROID]"))
        assertTrue(patched.contains("BOOT = system/first.iet"))
        // movie 解出且 XOR 解密还原
        assertEquals(String(movie, Charsets.US_ASCII), File(dir, "movie/op.mp4").readText(Charsets.US_ASCII))
        // 普通数据不解出
        assertFalse(File(dir, "data/script.tjs").exists())
    }

    @Test
    fun applyBasePatch_fallbackGeneratesSystemIniWhenAbsent() {
        val dir = tempDir()
        val movie = "MOVIEMOVIEMOVIE".toByteArray(Charsets.US_ASCII)
        File(dir, "root.pfs").writeBytes(buildPfs(listOf("movie/op.mp4" to movie)))

        assertTrue(ArtemisPfsUnpacker.applyBasePatch(dir.path))
        assertTrue(File(dir, "system.ini").exists())
        assertTrue(File(dir, "system.ini").readText(Charsets.UTF_8).contains("FONT_CACHE_SIZE = 8388608"))
    }

    @Test
    fun applyBasePatch_transformsListWindowsTblIntoListAndroidTbl() {
        val dir = tempDir()
        val tbl = ("config_tablet=0\nconfig_tabletui=0\nSIDECUT = 0\n").toByteArray(Charsets.UTF_8)
        File(dir, "root.pfs").writeBytes(buildPfs(listOf("list_windows.tbl" to tbl)))

        assertTrue(ArtemisPfsUnpacker.applyBasePatch(dir.path))

        // 改名：list_windows.tbl -> list_android.tbl
        assertFalse(File(dir, "list_windows.tbl").exists())
        val target = File(dir, "list_android.tbl")
        assertTrue("list_android.tbl should exist", target.exists())
        val content = target.readText(Charsets.UTF_8)
        // config_tablet / config_tabletui 翻转，其余行原样保留
        assertTrue("content=[$content]", content.contains("config_tablet=1,"))
        assertTrue(content.contains("config_tabletui=1,"))
        assertFalse(content.contains("config_tablet=0"))
        assertFalse(content.contains("config_tabletui=0"))
        assertTrue(content.contains("SIDECUT = 0"))
    }

    @Test
    fun applyBasePatch_plainListWindowsRenamedNoTbl() {
        val dir = tempDir()
        val list = "a.dat\nb.dat\n".toByteArray(Charsets.UTF_8)
        File(dir, "root.pfs").writeBytes(buildPfs(listOf("list_windows" to list)))

        assertTrue(ArtemisPfsUnpacker.applyBasePatch(dir.path))

        // 纯 list_windows 仅改名 list_android，不生成 list_android.tbl
        assertTrue(File(dir, "list_android").exists())
        assertFalse(File(dir, "list_android.tbl").exists())
    }

    /**
     * 构造符合逆向格式的 .pfs：
     * magic "pf"+版本 | 表长(LE) | 表内容(条目数 + 条目表) | XOR 加密数据区。
     * 密钥 = SHA-1(表内容)；每个条目数据独立以 key[0..] 加密（>= 8 字节）。
     */
    private fun buildPfs(entries: List<Pair<String, ByteArray>>): ByteArray {
        // 1) 表内容（offset/dataLen 先占位 0）
        val tableContent = ByteArrayOutputStream().apply { write(leInt(entries.size)) }
        val fields = mutableListOf<Pair<Int, Int>>() // (offset 字段位, dataLen 字段位) 相对表内容
        entries.forEach { (name, _) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            tableContent.write(leInt(nameBytes.size))
            tableContent.write(nameBytes)
            tableContent.write(leInt(0)) // unknown 字段
            val offsetPos = tableContent.size()
            tableContent.write(leInt(0))
            val dataLenPos = tableContent.size()
            tableContent.write(leInt(0))
            fields.add(offsetPos to dataLenPos)
        }
        // 2) 填充实际 offset/dataLen → 最终表内容（密钥依赖该字节流，须先于加密确定）
        val tableBytes = tableContent.toByteArray()
        var running = 7 + tableBytes.size
        fields.forEachIndexed { index, (offsetPos, dataLenPos) ->
            val len = entries[index].second.size
            leInt(running).copyInto(tableBytes, offsetPos)
            leInt(len).copyInto(tableBytes, dataLenPos)
            running += len
        }
        // 3) 密钥 = SHA-1(最终表内容)，逐条目独立加密数据（>= 8 字节）
        val key = MessageDigest.getInstance("SHA-1").digest(tableBytes)
        val encrypted = ByteArrayOutputStream()
        entries.forEach { (_, data) ->
            val enc = data.clone()
            for (j in enc.indices) {
                enc[j] = (enc[j].toInt() xor key[j % key.size].toInt()).toByte()
            }
            encrypted.write(enc)
        }
        // 4) 拼接：magic "pf"+版本 | 表长(LE) | 表内容 | 加密数据区
        return ByteArrayOutputStream().apply {
            write('p'.code)
            write('f'.code)
            write('1'.code)
            write(leInt(tableBytes.size))
            write(tableBytes)
            write(encrypted.toByteArray())
        }.toByteArray()
    }

    private fun leInt(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "pfs_test_${System.nanoTime()}").apply { mkdirs() }
}
