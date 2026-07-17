package com.yuki.yukihub.tyrano;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal ASAR reader for Tyrano/NW.js style packages.
 *
 * Supports the common Electron ASAR layout used by Tyranor:
 * - header magic/version = 4
 * - header size
 * - json length
 * - header json
 * - file data area
 */
public class AsarArchive implements Closeable {
    private static final String TAG = "YukiAsar";
    private static final long MAX_HEADER_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;

    private final File archiveFile;
    private final RandomAccessFile raf;
    private final long dataOffset;
    private final Map<String, Entry> entries = new HashMap<>();

    public AsarArchive(File file) throws Exception {
        this.archiveFile = file == null ? null : file.getCanonicalFile();
        if (this.archiveFile == null || !this.archiveFile.isFile()) {
            throw new IllegalArgumentException("asar file missing");
        }
        RandomAccessFile opened = new RandomAccessFile(this.archiveFile, "r");
        long parsedDataOffset;
        try {
            int magic = readIntLE(opened);
            if (magic != 4) throw new IllegalStateException("not asar file");
            long headerSize = readUInt32LE(opened);
            if (headerSize < 8L || headerSize > MAX_HEADER_BYTES || 8L + headerSize > opened.length()) {
                throw new IOException("invalid asar header size: " + headerSize);
            }
            parsedDataOffset = 8L + headerSize;
            opened.skipBytes(4);
            long jsonLen = readUInt32LE(opened);
            if (jsonLen <= 0L || jsonLen > MAX_HEADER_BYTES || opened.getFilePointer() + jsonLen > parsedDataOffset) {
                throw new IOException("invalid asar json size: " + jsonLen);
            }
            byte[] jsonBytes = new byte[(int) jsonLen];
            opened.readFully(jsonBytes);
            parseNode("", new JSONObject(new String(jsonBytes, StandardCharsets.UTF_8)));
            validateEntries(opened.length(), parsedDataOffset);
        } catch (Exception error) {
            try { opened.close(); } catch (Throwable ignored) { }
            throw error;
        } catch (Error error) {
            try { opened.close(); } catch (Throwable ignored) { }
            throw error;
        }
        this.raf = opened;
        this.dataOffset = parsedDataOffset;
        Log.i(TAG, "asar loaded file=" + this.archiveFile + " entries=" + entries.size() + " dataOffset=" + dataOffset);
    }

    public boolean has(String path) {
        return entries.containsKey(normalize(path));
    }

    public boolean isDirectory(String path) {
        Entry e = entries.get(normalize(path));
        return e != null && e.directory;
    }

    public byte[] read(String path) {
        try {
            Entry e = entries.get(normalize(path));
            if (e == null || e.directory) return null;
            byte[] data = new byte[(int) e.size];
            synchronized (raf) {
                raf.seek(dataOffset + e.offset);
                raf.readFully(data);
            }
            return data;
        } catch (Throwable t) {
            Log.w(TAG, "read failed path=" + path, t);
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (raf) {
            raf.close();
        }
    }

    private void validateEntries(long archiveLength, long parsedDataOffset) throws IOException {
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry == null || entry.directory) continue;
            if (entry.size < 0L || entry.size > MAX_ENTRY_BYTES || entry.size > Integer.MAX_VALUE) {
                throw new IOException("invalid asar entry size: " + item.getKey());
            }
            if (entry.offset < 0L || entry.offset > archiveLength - parsedDataOffset
                    || entry.size > archiveLength - parsedDataOffset - entry.offset) {
                throw new IOException("invalid asar entry range: " + item.getKey());
            }
        }
    }

    public String getArchiveName() {
        return archiveFile == null ? "" : archiveFile.getName();
    }

    private void parseNode(String prefix, JSONObject node) throws Exception {
        JSONObject files = node.optJSONObject("files");
        if (files == null) {
            if (!prefix.isEmpty()) {
                long size = parseLong(node.optString("size", "0"));
                long offset = parseLong(node.optString("offset", "0"));
                entries.put(normalize(prefix), new Entry(false, size, offset));
            }
            return;
        }
        if (!prefix.isEmpty()) {
            entries.put(normalize(prefix), new Entry(true, 0, 0));
        }
        Iterator<String> keys = files.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONObject child = files.optJSONObject(name);
            if (child == null) continue;
            String next = prefix.isEmpty() ? name : prefix + "/" + name;
            parseNode(next, child);
        }
    }

    private static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static int readIntLE(RandomAccessFile raf) throws Exception {
        return (int) readUInt32LE(raf);
    }

    private static long readUInt32LE(RandomAccessFile raf) throws Exception {
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        int b4 = raf.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new java.io.EOFException();
        return ((long) b4 << 24) | ((long) b3 << 16) | ((long) b2 << 8) | (long) b1;
    }

    private static long parseLong(String value) {
        try {
            if (value == null || value.trim().isEmpty()) return 0L;
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static final class Entry {
        final boolean directory;
        final long size;
        final long offset;

        Entry(boolean directory, long size, long offset) {
            this.directory = directory;
            this.size = size;
            this.offset = offset;
        }
    }
}
