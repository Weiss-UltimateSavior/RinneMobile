package com.yuki.yukihub.importer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 导入器共享 IO 工具：统一处理 Zip Bomb、Zip Slip、临时目录清理。
 *
 * - {@link #readBytes(InputStream, long, ReadAccumulator)} 限制单个 entry 读取字节数 +
 *   通过 {@link ReadAccumulator} 跟踪累计字节，避免恶意 ZIP 用多个 49MB entry 绕过单条限制。
 * - {@link #ensurePathInside(File, File)} 通过 canonical path 校验防止 zip slip 路径穿越。
 * - {@link #skipFully(InputStream, long)} 拒绝 entry 时不分配完整 byte[]，分块跳过。
 * - {@link #MAX_ENTRY_COUNT} 限制 ZIP entry 数量，避免恶意 ZIP 用数百万空 entry 耗尽资源。
 * - {@link #registerTempDir(File)} + {@link #cleanupAllTempDirs()} 替代 deleteOnExit()
 *   （Android 进程几乎不退出，shutdown hook 不会执行，deleteOnExit 形同虚设）。
 */
final class ImporterIO {

    /** 单个 entry 最大字节数：50MB（足够容纳正常封面图与 JSON） */
    static final long MAX_ENTRY_BYTES = 50L * 1024 * 1024;

    /** 累计最大字节数：200MB（防止恶意 ZIP 用多个 entry 累积内存） */
    static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    /** ZIP entry 最大数量：10000（防止恶意 ZIP 用海量空 entry 耗尽资源） */
    static final int MAX_ENTRY_COUNT = 10000;

    /** 已注册的临时目录，由 ImporterService 写库完成后统一清理 */
    private static final ConcurrentMap<File, Boolean> tempDirs = new ConcurrentHashMap<>();

    private ImporterIO() {
    }

    /**
     * 累积字节计数器，用于在解压循环中跟踪总字节数。
     * 单条 entry 检查 {@link #MAX_ENTRY_BYTES}，累计检查 {@link #MAX_TOTAL_BYTES}。
     * 每次解压在 parse() 入口 new 一个，循环中复用。
     */
    static final class ReadAccumulator {
        long total;
        final long maxTotal;

        ReadAccumulator(long maxTotal) {
            this.maxTotal = maxTotal;
        }
    }

    /**
     * 读取流到 byte[]，超过 maxBytes 或累计超过 acc.maxTotal 抛 IOException。
     * 防止恶意大文件触发 OOM。
     *
     * @param acc 累积计数器，null 表示不跟踪累计（用于单文件场景如 Playnite）
     */
    static byte[] readBytes(InputStream in, long maxBytes, ReadAccumulator acc) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int len;
        long entryTotal = 0;
        while ((len = in.read(tmp)) > 0) {
            entryTotal += len;
            if (entryTotal > maxBytes) {
                throw new IOException("条目大小超过上限 " + (maxBytes / 1024 / 1024) + "MB");
            }
            if (acc != null) {
                acc.total += len;
                if (acc.total > acc.maxTotal) {
                    throw new IOException("累计大小超过上限 " + (acc.maxTotal / 1024 / 1024) + "MB");
                }
            }
            baos.write(tmp, 0, len);
        }
        return baos.toByteArray();
    }

    /** 读取流到 String，超过 maxBytes 抛 IOException。无累计跟踪（单文件场景）。 */
    static String readString(InputStream in, long maxBytes, Charset charset) throws IOException {
        return new String(readBytes(in, maxBytes, null), charset);
    }

    /** 读取流到 String，超过 maxBytes 或累计超过 acc.maxTotal 抛 IOException。 */
    static String readString(InputStream in, long maxBytes, Charset charset, ReadAccumulator acc)
            throws IOException {
        return new String(readBytes(in, maxBytes, acc), charset);
    }

    /**
     * 分块跳过指定字节数，不分配完整 byte[] 缓冲区。
     * 用于 zip slip 拒绝 entry 时维持流进度，避免无谓的 50MB 堆分配。
     */
    static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] tmp = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(remaining, tmp.length);
            int len = in.read(tmp, 0, toRead);
            if (len <= 0) break;
            remaining -= len;
        }
    }

    /**
     * 校验 outFile 的 canonical path 必须位于 baseDir 之内，
     * 防止 zip slip 通过 "../" 或绝对路径逃逸。
     */
    static void ensurePathInside(File outFile, File baseDir) throws IOException {
        String basePath = baseDir.getCanonicalPath();
        String outPath = outFile.getCanonicalPath();
        if (!outPath.startsWith(basePath + File.separator) && !outPath.equals(basePath)) {
            throw new IOException("路径穿越检测： " + outPath + " 不在 " + basePath + " 之内");
        }
    }

    /** 注册临时目录，待 ImporterService 写库完成后统一清理。 */
    static void registerTempDir(File dir) {
        if (dir != null) tempDirs.put(dir, Boolean.TRUE);
    }

    /** 递归删除所有已注册的临时目录。在 ImporterService.importSelected 完成后调用。 */
    static void cleanupAllTempDirs() {
        for (File dir : tempDirs.keySet()) {
            deleteRecursively(dir);
            tempDirs.remove(dir);
        }
    }

    /** 递归删除文件或目录。 */
    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
