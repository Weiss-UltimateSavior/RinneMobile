package bridge;

import android.content.ContentResolver;
import android.content.UriPermission;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.OsConstants;
import android.util.Log;

import org.tvp.kirikiri2.KR2Activity;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;
import androidx.documentfile.provider.DocumentFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class NativeBridge {
    private static final List<RandomAccessFile> OPEN_FILES = new ArrayList<>();
    private static final List<ParcelFileDescriptor> OPEN_PFDS = new ArrayList<>();

    private NativeBridge() { }

    public static native boolean initialize(String so);
    public static native boolean launch(String so, String path, boolean useMaps);
    public static native void interceptor(String prefix);
    public static native void relocate();
    public static native boolean write(String path, byte[] data);

    public static synchronized int open(String path, int mode) {
        String normalized = normalizeFilePath(path);
        String redirected = redirectKrScopedSavePath(normalized, mode);
        if (redirected != null) normalized = redirected;
        String javaMode;
        try {
            javaMode = toJavaMode(mode);
        } catch (Throwable t) {
            Log.e("NativeBridge", "bad open mode=" + mode + " path=" + path, t);
            return -1;
        }

        try {
            RandomAccessFile raf = new RandomAccessFile(new File(normalized), javaMode);
            OPEN_FILES.add(raf);
            int fd = getFd(raf);
            Log.i("NativeBridge", "open " + fd + " " + javaMode + " " + path);
            return fd;
        } catch (Throwable directError) {
            int safFd = openViaSaf(normalized, mode, directError);
            if (safFd >= 0) return safFd;
            Log.e("NativeBridge", "open failed mode=" + mode + " path=" + path, directError);
            return -1;
        }
    }

    private static String redirectKrScopedSavePath(String path, int mode) {
        try {
            KR2Activity activity = KR2Activity.getInstance();
            if (activity == null) activity = KR2Activity.GetInstance();
            if (activity == null || activity.getIntent() == null) return null;
            if (!activity.getIntent().getBooleanExtra("scopedSaveDir", false)) return null;
            if (path == null || path.trim().isEmpty()) return null;
            String p = normalizeFilePath(path);
            String lower = p.toLowerCase();
            int idx = lower.indexOf("/savedata/");
            int folderLen = "/savedata/".length();
            if (idx < 0) {
                if (lower.endsWith("/savedata")) {
                    idx = lower.length() - "/savedata".length();
                    folderLen = "/savedata".length();
                } else {
                    return null;
                }
            }
            String rel = p.length() > idx + folderLen ? p.substring(idx + folderLen) : "";
            File base = new File(activity.getExternalFilesDir(null), "save");
            String name = activity.getIntent().getStringExtra("scopedSaveName");
            File dir = new File(base, (name == null || name.trim().isEmpty()) ? "default" : name);
            File out = rel.isEmpty() ? dir : new File(dir, rel);
            File parent = out.isDirectory() ? out : out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Log.i("NativeBridge", "redirect KR save " + p + " -> " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w("NativeBridge", "redirect KR save failed path=" + path, t);
            return null;
        }
    }

    private static int openViaSaf(String path, int mode, Throwable directError) {
        try {
            Uri uri = storagePathToPersistedDocumentUri(path, mode);
            if (uri == null) return -1;
            KR2Activity activity = KR2Activity.getInstance();
            if (activity == null) activity = KR2Activity.GetInstance();
            if (activity == null) return -1;
            String pfdMode = toPfdMode(mode);
            ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(uri, pfdMode);
            if (pfd == null) return -1;
            OPEN_PFDS.add(pfd);
            int fd = pfd.getFd();
            Log.i("NativeBridge", "open SAF " + fd + " " + pfdMode + " " + path + " -> " + uri);
            return fd;
        } catch (Throwable safError) {
            Log.w("NativeBridge", "open SAF fallback failed path=" + path + " direct=" + directError, safError);
            return -1;
        }
    }

    private static Uri storagePathToPersistedDocumentUri(String path, int mode) {
        if (path == null) return null;
        String p = normalizeFilePath(path);
        if (!p.startsWith("/storage/")) return null;
        if (p.startsWith("/storage/emulated/0/")) return null;
        String rest = p.substring("/storage/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String volume = rest.substring(0, slash);
        String rel = rest.substring(slash + 1);
        if (volume.isEmpty() || rel.isEmpty()) return null;

        KR2Activity activity = KR2Activity.getInstance();
        if (activity == null) activity = KR2Activity.GetInstance();
        if (activity == null) return null;
        ContentResolver resolver = activity.getContentResolver();
        String docId = volume + ":" + rel;
        int permissionCount = resolver.getPersistedUriPermissions().size();
        Log.i("NativeBridge", "SAF resolve path=" + path + " volume=" + volume + " rel=" + rel + " persisted=" + permissionCount);
        for (UriPermission perm : resolver.getPersistedUriPermissions()) {
            Uri tree = perm.getUri();
            if (tree == null) continue;
            String treeId;
            try { treeId = DocumentsContract.getTreeDocumentId(tree); } catch (Throwable ignored) { continue; }
            if (treeId == null) continue;
            String decodedTreeId = Uri.decode(treeId);
            Log.i("NativeBridge", "SAF candidate tree=" + decodedTreeId + " uri=" + tree);
            if (!decodedTreeId.startsWith(volume + ":")) continue;
            String treeRel = decodedTreeId.substring((volume + ":").length());
            if (!treeRel.isEmpty()) {
                if (!rel.equals(treeRel) && !rel.startsWith(treeRel + "/")) continue;
            }
            Uri existing = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            if (!needsCreate(mode)) return existing;
            Uri created = ensureDocumentExists(resolver, tree, decodedTreeId, volume, rel);
            return created == null ? existing : created;
        }
        return null;
    }

    private static boolean needsCreate(int mode) {
        int accessMode = mode & OsConstants.O_ACCMODE;
        return accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR || (mode & OsConstants.O_CREAT) == OsConstants.O_CREAT;
    }

    private static Uri ensureDocumentExists(ContentResolver resolver, Uri tree, String decodedTreeId, String volume, String rel) {
        try {
            KR2Activity activity = KR2Activity.getInstance();
            if (activity == null) activity = KR2Activity.GetInstance();
            if (activity == null) return null;
            DocumentFile dir = DocumentFile.fromTreeUri(activity, tree);
            if (dir == null) return null;
            String treePrefix = volume + ":";
            String treeRel = decodedTreeId != null && decodedTreeId.startsWith(treePrefix) ? decodedTreeId.substring(treePrefix.length()) : "";
            String localRel = rel;
            if (!treeRel.isEmpty()) {
                if (localRel.equals(treeRel)) return DocumentsContract.buildDocumentUriUsingTree(tree, volume + ":" + rel);
                if (localRel.startsWith(treeRel + "/")) localRel = localRel.substring(treeRel.length() + 1);
            }
            String[] parts = localRel.split("/");
            DocumentFile current = dir;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part == null || part.isEmpty() || ".".equals(part)) continue;
                boolean last = i == parts.length - 1;
                DocumentFile child = current.findFile(part);
                if (last) {
                    if (child == null) child = current.createFile(guessMime(part), part);
                    return child == null ? null : child.getUri();
                }
                if (child == null) child = current.createDirectory(part);
                if (child == null || !child.isDirectory()) return null;
                current = child;
            }
        } catch (Throwable t) {
            Log.w("NativeBridge", "ensure SAF document failed rel=" + rel, t);
        }
        return null;
    }

    private static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".tjs") || lower.endsWith(".ks") || lower.endsWith(".xml") || lower.endsWith(".json")) return "text/plain";
        return "application/octet-stream";
    }

    private static String normalizeFilePath(String path) {
        if (path == null) return path;
        if (path.startsWith("file://")) return path.substring("file://".length());
        return path;
    }

    private static String toJavaMode(int mode) {
        int accessMode = mode & OsConstants.O_ACCMODE;
        if (accessMode == OsConstants.O_RDONLY) return "r";
        if (accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR) return "rw";
        throw new IllegalArgumentException("Bad mode: " + mode);
    }

    private static String toPfdMode(int mode) {
        int accessMode = mode & OsConstants.O_ACCMODE;
        if (accessMode == OsConstants.O_RDONLY) return "r";
        if ((mode & OsConstants.O_APPEND) == OsConstants.O_APPEND) return "wa";
        if ((mode & OsConstants.O_TRUNC) == OsConstants.O_TRUNC) return "wt";
        if (accessMode == OsConstants.O_WRONLY) return "w";
        if (accessMode == OsConstants.O_RDWR) return "rw";
        throw new IllegalArgumentException("Bad mode: " + mode);
    }

    private static int getFd(RandomAccessFile raf) throws Exception {
        FileDescriptor fd = raf.getFD();
        Method method = FileDescriptor.class.getDeclaredMethod("getInt$");
        method.setAccessible(true);
        return (Integer) method.invoke(fd);
    }
}