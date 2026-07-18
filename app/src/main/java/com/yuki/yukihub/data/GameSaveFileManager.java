package com.yuki.yukihub.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.launcher.EmulatorLauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Minimal manager for a game's real save directory.
 *
 * <p>Game saves are local directories. Export additionally supports a SAF
 * destination tree so callers can use the system folder picker.</p>
 */
public final class GameSaveFileManager {
    private static final String PREFS_NAME = "yukihub_game_save_paths";
    private static final String KEY_PREFIX = "save_path.";
    private static final int BUFFER_SIZE = 64 * 1024;
    // A save backup should never contain an engine payload. This is also a
    // practical upper bound for screenshot-heavy saves while rejecting a
    // mistakenly selected multi-gigabyte game archive.
    private static final long MAX_SAVE_ZIP_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_SAVE_ZIP_FILES = 4_000;

    private final SharedPreferences prefs;
    private final Context context;

    public GameSaveFileManager(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Resolves the real save location used by a supported built-in engine. */
    public SaveLocation resolveInternalSaveLocation(Game game) {
        if (game == null || game.engine == null) return SaveLocation.unavailable("游戏或引擎信息不可用");
        if (!isBuiltInPackage(game)) return SaveLocation.unavailable("该游戏使用外置模拟器，不纳入存档管理");

        EmulatorLauncher.ActualSaveLocation location = EmulatorLauncher.resolveActualSaveLocation(
                context, game.engine, game.rootUri, game.launchTarget, game.id);
        return location.available && location.directory != null
                ? SaveLocation.available(location.directory, location.description)
                : SaveLocation.unavailable(location.description);
    }

    /** Lists files from the automatically resolved built-in save location. */
    public List<File> listInternalSaveFiles(Game game) {
        SaveLocation location = resolveInternalSaveLocation(game);
        if (!location.available || location.directory == null) return Collections.emptyList();
        List<File> files = new ArrayList<>();
        for (File directory : resolveInternalSaveDirectories(game, location)) {
            if (directory.isDirectory()) collectFiles(directory, files);
        }
        return files;
    }

    /** Exports the automatically resolved built-in save directory. */
    public int exportInternalSave(Game game, File destinationDirectory) throws IOException {
        SaveLocation location = resolveInternalSaveLocation(game);
        if (!location.available || location.directory == null) throw new IOException(location.reason);
        File source = requireExistingDirectory(location.directory, "游戏存档目录");
        rejectGamePayload(source);
        File destination = requireDirectory(destinationDirectory, "导出目录");
        rejectNestedDirectories(source, destination);
        return copyDirectoryContents(source, destination, false);
    }

    /** Exports the resolved save files into a directory selected through the system picker. */
    public int exportInternalSaveToTree(Game game, Uri destinationTreeUri) throws IOException {
        SaveLocation location = resolveInternalSaveLocation(game);
        if (!location.available || location.directory == null) throw new IOException(location.reason);
        File source = requireExistingDirectory(location.directory, "游戏存档目录");
        rejectGamePayload(source);
        if (destinationTreeUri == null) throw new IOException("导出目录不可用");
        DocumentFile destination = DocumentFile.fromTreeUri(context, destinationTreeUri);
        if (destination == null || !destination.isDirectory()) throw new IOException("无法打开导出目录");
        return copyDirectoryContentsToDocument(source, destination);
    }

    /** Exports all real save files as one ZIP archive selected through the system file picker. */
    public int exportInternalSaveToZip(Game game, Uri destinationUri) throws IOException {
        SaveLocation location = resolveInternalSaveLocation(game);
        if (!location.available || location.directory == null) throw new IOException(location.reason);
        if (destinationUri == null) throw new IOException("导出文件不可用");
        List<File> sources = resolveInternalSaveDirectories(game, location);
        try (OutputStream raw = context.getContentResolver().openOutputStream(destinationUri, "w")) {
            if (raw == null) throw new IOException("无法创建导出压缩包");
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                int written = 0;
                Set<String> entries = new HashSet<>();
                for (File source : sources) {
                    if (!source.isDirectory()) continue;
                    rejectGamePayload(source);
                    written += writeZipContents(source, source, zip, entries);
                }
                if (written == 0) throw new IOException("暂未发现可导出的存档文件");
                return written;
            }
        }
    }

    /** Imports into the automatically resolved built-in save directory. */
    public int importInternalSave(Game game, File sourceDirectory, boolean overwrite) throws IOException {
        SaveLocation location = resolveInternalSaveLocation(game);
        if (!location.available || location.directory == null) throw new IOException(location.reason);
        File source = requireExistingDirectory(sourceDirectory, "导入目录");
        File destination = requireDirectory(location.directory, "游戏存档目录");
        rejectNestedDirectories(source, destination);
        if (samePath(source, destination)) throw new IOException("导入目录与游戏存档目录相同");
        if (overwrite) clearDirectory(destination);
        return copyDirectoryContents(source, destination, false);
    }

    /** Imports from a directory selected through the system picker. */
    public int importInternalSaveFromTree(Game game, Uri sourceTreeUri, boolean overwrite) throws IOException {
        SaveLocation location = resolveInternalSaveLocation(game);
        if (!location.available || location.directory == null) throw new IOException(location.reason);
        if (sourceTreeUri == null) throw new IOException("导入目录不可用");
        DocumentFile source = DocumentFile.fromTreeUri(context, sourceTreeUri);
        if (source == null || !source.isDirectory()) throw new IOException("无法打开导入目录");
        File destination = requireDirectory(location.directory, "游戏存档目录");
        if (overwrite) clearDirectory(destination);
        return copyDocumentContentsToDirectory(source, destination);
    }

    /** Imports a ZIP archive selected through the system file picker. */
    public int importInternalSaveFromZip(Game game, Uri sourceUri, boolean overwrite) throws IOException {
        if (sourceUri == null) throw new IOException("导入压缩包不可用");
        File temporaryDirectory = createTemporaryImportDirectory();
        try {
            int extracted = extractZipToDirectory(sourceUri, temporaryDirectory);
            if (extracted == 0) throw new IOException("压缩包中未找到存档文件");
            SaveLocation location = resolveInternalSaveLocation(game);
            if (!location.available || location.directory == null) throw new IOException(location.reason);
            List<File> destinations = resolveInternalSaveDirectories(game, location);
            if (destinations.isEmpty()) throw new IOException("无法解析实际存档目录");
            for (File destination : destinations) {
                destination = requireDirectory(destination, "游戏存档目录");
                if (overwrite) clearDirectory(destination);
            }
            int copied = 0;
            for (File destination : destinations) {
                copied = Math.max(copied, copyDirectoryContents(temporaryDirectory,
                        requireDirectory(destination, "游戏存档目录"), false));
            }
            return copied;
        } finally {
            try { deleteRecursively(temporaryDirectory); } catch (Throwable ignored) { }
        }
    }

    private static boolean isBuiltInPackage(Game game) {
        String pkg = game.emulatorPackage == null ? "" : game.emulatorPackage.trim().toLowerCase(Locale.ROOT);
        if (pkg.isEmpty()) return game.engine == EngineType.KIRIKIRI || game.engine == EngineType.ARTEMIS
                || game.engine == EngineType.ONS || game.engine == EngineType.TYRANO;
        switch (game.engine) {
            case KIRIKIRI: return pkg.startsWith("internal.krkr") || "org.tvp.kirikiri2.internal".equals(pkg);
            case ARTEMIS: return pkg.startsWith("internal.artemis");
            case ONS: return pkg.startsWith("internal.ons") || "com.yuki.yukihub.ons".equals(pkg);
            case TYRANO: return pkg.startsWith("internal.tyrano") || "com.yuki.yukihub.tyrano".equals(pkg);
            default: return false;
        }
    }

    public static final class SaveLocation {
        @Nullable public final File directory;
        public final String reason;
        public final boolean available;

        private SaveLocation(@Nullable File directory, String reason, boolean available) {
            this.directory = directory;
            this.reason = reason == null ? "" : reason;
            this.available = available;
        }

        private static SaveLocation available(File directory, String description) {
            return new SaveLocation(directory, description, true);
        }

        private static SaveLocation unavailable(String reason) {
            return new SaveLocation(null, reason, false);
        }
    }

    /** Records a writable local directory for the supplied game. */
    public boolean recordSaveDirectory(Game game, File saveDirectory) throws IOException {
        String key = gameKey(game);
        File directory = requireDirectory(saveDirectory, "存档目录");
        return prefs.edit().putString(KEY_PREFIX + key, directory.getCanonicalPath()).commit();
    }

    /** Returns the recorded directory, or {@code null} if no valid directory is recorded. */
    @Nullable
    public File getSaveDirectory(Game game) {
        String path = prefs.getString(KEY_PREFIX + gameKey(game), null);
        if (path == null || path.trim().isEmpty()) return null;
        File directory = new File(path);
        return directory.isDirectory() ? directory : null;
    }

    /** Removes only the path record; it never deletes the actual save files. */
    public boolean forgetSaveDirectory(Game game) {
        return prefs.edit().remove(KEY_PREFIX + gameKey(game)).commit();
    }

    /** Lists every regular save file below the recorded directory. */
    public List<File> listSaveFiles(Game game) {
        File directory = getSaveDirectory(game);
        if (directory == null) return Collections.emptyList();
        List<File> files = new ArrayList<>();
        collectFiles(directory, files);
        return files;
    }

    /**
     * Exports the recorded save directory's contents into {@code destinationDirectory}.
     * Existing destination files are not replaced.
     */
    public int exportSave(Game game, File destinationDirectory) throws IOException {
        File source = requireRecordedDirectory(game);
        File destination = requireDirectory(destinationDirectory, "导出目录");
        rejectNestedDirectories(source, destination);
        return copyDirectoryContents(source, destination, false);
    }

    /**
     * Imports a directory into the recorded save directory.
     *
     * @param overwrite when true, clears the recorded directory first; when false,
     *                  importing a file that already exists fails.
     */
    public int importSave(Game game, File sourceDirectory, boolean overwrite) throws IOException {
        File source = requireExistingDirectory(sourceDirectory, "导入目录");
        File destination = requireRecordedDirectory(game);
        rejectNestedDirectories(source, destination);
        if (samePath(source, destination)) throw new IOException("导入目录与存档目录相同");
        if (overwrite) clearDirectory(destination);
        return copyDirectoryContents(source, destination, false);
    }

    /** Equivalent to {@link #importSave(Game, File, boolean) importSave(game, source, true)}. */
    public int overwriteSave(Game game, File sourceDirectory) throws IOException {
        return importSave(game, sourceDirectory, true);
    }

    private File requireRecordedDirectory(Game game) throws IOException {
        File directory = getSaveDirectory(game);
        if (directory == null) throw new IOException("未记录有效的游戏存档目录");
        return directory;
    }

    private static File requireDirectory(File directory, String label) throws IOException {
        if (directory == null) throw new IOException(label + "不能为空");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建" + label + "：" + directory);
        if (!directory.isDirectory()) throw new IOException(label + "不是目录：" + directory);
        return directory.getCanonicalFile();
    }

    private static File requireExistingDirectory(File directory, String label) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            throw new IOException(label + "不存在或不是目录：" + directory);
        }
        return directory.getCanonicalFile();
    }

    private static void collectFiles(File directory, List<File> output) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectFiles(child, output);
            else if (child.isFile()) output.add(child);
        }
    }

    private List<File> resolveInternalSaveDirectories(Game game, SaveLocation primary) {
        List<File> directories = EmulatorLauncher.resolveActualSaveDirectories(
                context, game.engine, game.rootUri, game.launchTarget, game.id);
        if (directories == null || directories.isEmpty()) {
            return primary == null || primary.directory == null
                    ? Collections.emptyList() : Collections.singletonList(primary.directory);
        }
        return directories;
    }

    private int writeZipContents(File root, File directory, ZipOutputStream zip, Set<String> entries) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return 0;
        int written = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                written += writeZipContents(root, child, zip, entries);
            } else if (child.isFile()) {
                String relative = root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
                // App-private callback saves take priority if both KRKR paths
                // contain a file with the same relative name.
                if (entries != null && !entries.add(relative)) continue;
                ZipEntry entry = new ZipEntry(relative);
                entry.setTime(child.lastModified());
                zip.putNextEntry(entry);
                try (InputStream input = new FileInputStream(child)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
                } finally {
                    zip.closeEntry();
                }
                written++;
            }
        }
        return written;
    }

    private File createTemporaryImportDirectory() throws IOException {
        File cache = context.getCacheDir();
        if (cache == null) throw new IOException("应用缓存目录不可用");
        File directory = File.createTempFile("save_zip_", "", cache);
        if (!directory.delete() || !directory.mkdirs()) {
            throw new IOException("无法创建临时解压目录");
        }
        return directory.getCanonicalFile();
    }

    private int extractZipToDirectory(Uri sourceUri, File destination) throws IOException {
        String rootPath = destination.getCanonicalPath();
        Set<String> entries = new HashSet<>();
        int extracted = 0;
        long totalBytes = 0L;
        try (InputStream raw = context.getContentResolver().openInputStream(sourceUri)) {
            if (raw == null) throw new IOException("无法读取导入压缩包");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buffer = new byte[BUFFER_SIZE];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = safeZipEntryName(entry.getName());
                    rejectGamePayloadEntry(name);
                    if (!entries.add(name)) throw new IOException("压缩包包含重复文件：" + name);
                    if (entries.size() > MAX_SAVE_ZIP_FILES) {
                        throw new IOException("压缩包文件数量过多，不是有效的存档备份");
                    }
                    if (entry.getSize() > MAX_SAVE_ZIP_BYTES) {
                        throw new IOException("压缩包包含过大的文件，不是有效的存档备份：" + name);
                    }
                    File output = new File(destination, name).getCanonicalFile();
                    if (!output.getPath().startsWith(rootPath + File.separator)) {
                        throw new IOException("压缩包包含非法路径：" + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        if (!output.exists() && !output.mkdirs()) throw new IOException("无法创建存档目录：" + name);
                    } else {
                        File parent = output.getParentFile();
                        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                            throw new IOException("无法创建存档目录：" + name);
                        }
                        try (OutputStream out = new FileOutputStream(output, false)) {
                            int read;
                            while ((read = zip.read(buffer)) != -1) {
                                totalBytes += read;
                                if (totalBytes > MAX_SAVE_ZIP_BYTES) {
                                    throw new IOException("压缩包解压后过大，不是有效的存档备份");
                                }
                                out.write(buffer, 0, read);
                            }
                        }
                        if (entry.getTime() > 0L) output.setLastModified(entry.getTime());
                        extracted++;
                    }
                    zip.closeEntry();
                }
            }
        }
        return extracted;
    }

    private static String safeZipEntryName(String name) throws IOException {
        if (name == null) throw new IOException("压缩包包含无效文件名");
        String normalized = name.replace('\\', '/');
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("//")) {
            throw new IOException("压缩包包含非法路径：" + name);
        }
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("压缩包包含非法路径：" + name);
            }
        }
        return normalized;
    }

    /** Reject engine archives and native plug-ins; these belong to a game root, never a save backup. */
    private static void rejectGamePayloadEntry(String name) throws IOException {
        String normalized = name == null ? "" : name.replace('\\', '/').toLowerCase(Locale.ROOT);
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.startsWith("plugin/") || normalized.contains("/plugin/")
                || leaf.endsWith(".xp3") || leaf.endsWith(".pfs")
                || leaf.endsWith(".dll") || leaf.endsWith(".exe")
                || leaf.endsWith(".so") || leaf.endsWith(".apk") || leaf.endsWith(".obb")) {
            throw new IOException("压缩包包含游戏资源，不能作为存档导入：" + name);
        }
    }

    private static void rejectGamePayload(File directory) throws IOException {
        List<File> files = new ArrayList<>();
        collectFiles(directory, files);
        long totalBytes = 0L;
        for (File file : files) {
            String relative = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            rejectGamePayloadEntry(relative);
            totalBytes += Math.max(0L, file.length());
            if (totalBytes > MAX_SAVE_ZIP_BYTES) {
                throw new IOException("真实存档目录异常过大，疑似混入游戏资源；请先清理后再导出");
            }
        }
    }

    private static int copyDirectoryContents(File source, File destination, boolean replaceExisting) throws IOException {
        File[] children = source.listFiles();
        if (children == null) return 0;
        int copied = 0;
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (child.isDirectory()) {
                if (target.exists() && !target.isDirectory()) {
                    if (!replaceExisting) throw new IOException("目标文件已存在：" + target);
                    deleteRecursively(target);
                }
                if (!target.exists() && !target.mkdirs()) throw new IOException("无法创建目录：" + target);
                copied += copyDirectoryContents(child, target, replaceExisting);
            } else if (child.isFile()) {
                if (target.exists() && !replaceExisting) throw new IOException("目标文件已存在：" + target);
                copyFile(child, target);
                copied++;
            }
        }
        return copied;
    }

    private int copyDirectoryContentsToDocument(File source, DocumentFile destination) throws IOException {
        File[] children = source.listFiles();
        if (children == null) return 0;
        int copied = 0;
        for (File child : children) {
            DocumentFile target = destination.findFile(child.getName());
            if (child.isDirectory()) {
                if (target != null && !target.isDirectory()) throw new IOException("导出目录已存在同名文件：" + child.getName());
                if (target == null) target = destination.createDirectory(child.getName());
                if (target == null) throw new IOException("无法创建导出目录：" + child.getName());
                copied += copyDirectoryContentsToDocument(child, target);
            } else if (child.isFile()) {
                if (target != null) throw new IOException("导出目录已存在同名文件：" + child.getName());
                target = destination.createFile("application/octet-stream", child.getName());
                if (target == null) throw new IOException("无法创建导出文件：" + child.getName());
                copyFileToDocument(child, target);
                copied++;
            }
        }
        return copied;
    }

    private void copyFileToDocument(File source, DocumentFile target) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream in = new FileInputStream(source);
             java.io.OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "w")) {
            if (out == null) throw new IOException("无法写入导出文件：" + source.getName());
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private int copyDocumentContentsToDirectory(DocumentFile source, File destination) throws IOException {
        DocumentFile[] children = source.listFiles();
        if (children == null) return 0;
        int copied = 0;
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null || name.trim().isEmpty()) continue;
            File target = new File(destination, name);
            if (child.isDirectory()) {
                if (target.exists() && !target.isDirectory()) throw new IOException("游戏存档目录已存在同名文件：" + name);
                if (!target.exists() && !target.mkdirs()) throw new IOException("无法创建存档目录：" + name);
                copied += copyDocumentContentsToDirectory(child, target);
            } else if (child.isFile()) {
                if (target.exists()) throw new IOException("游戏存档目录已存在同名文件：" + name);
                copyDocumentToFile(child, target);
                copied++;
            }
        }
        return copied;
    }

    private void copyDocumentToFile(DocumentFile source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建存档目录：" + parent);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (java.io.InputStream in = context.getContentResolver().openInputStream(source.getUri());
             FileOutputStream out = new FileOutputStream(target, false)) {
            if (in == null) throw new IOException("无法读取导入文件：" + source.getName());
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        target.setLastModified(source.lastModified());
    }

    private static void clearDirectory(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) deleteRecursively(child);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("无法删除：" + file);
    }

    private static void rejectNestedDirectories(File source, File destination) throws IOException {
        String sourcePath = source.getCanonicalPath();
        String destinationPath = destination.getCanonicalPath();
        if (destinationPath.startsWith(sourcePath + File.separator)
                || sourcePath.startsWith(destinationPath + File.separator)) {
            throw new IOException("源目录与目标目录不能互为父子目录");
        }
    }

    private static boolean samePath(File first, File second) throws IOException {
        return first.getCanonicalPath().equals(second.getCanonicalPath());
    }

    private static String gameKey(Game game) {
        if (game == null) throw new IllegalArgumentException("game must not be null");
        String rootUri = GameRepository.normalizeRootUriKey(game.rootUri);
        if (!rootUri.isEmpty()) {
            return "root." + Base64.encodeToString(rootUri.getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        }
        if (game.id > 0) return "id." + game.id;
        throw new IllegalArgumentException("game must have rootUri or id");
    }
}
