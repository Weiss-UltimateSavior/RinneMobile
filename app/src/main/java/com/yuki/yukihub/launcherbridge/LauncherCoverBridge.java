package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.metadata.VndbClient;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public final class LauncherCoverBridge {
    private LauncherCoverBridge() {}

    public static void fetchCoverForGameAsync(Context context, Game game) {
        if (game == null || game.id <= 0) return;
        if (game.title == null || game.title.trim().isEmpty()) return;
        final Context appContext = context.getApplicationContext();
        final Game target = copyGame(game);
        AppExecutors.io().execute(() -> {
            try {
                List<VnMetadata> candidates = VndbClient.searchCandidates(target.title, 1);
                if (candidates == null || candidates.isEmpty()) return;
                VnMetadata meta = candidates.get(0);
                if (meta.coverUrl == null || meta.coverUrl.trim().isEmpty()) return;
                String cover = downloadAndSaveCover(appContext, meta.coverUrl, "scan_cover_" + target.id);
                if (cover == null) return;
                GameRepository repository = new GameRepository(appContext);
                Game latest = repository.findById(target.id);
                if (latest == null) return;
                if (latest.coverUri != null && !latest.coverUri.trim().isEmpty()) return;
                latest.coverUri = cover;
                latest.coverPersistUri = cover;
                latest.coverSourceType = 1;
                repository.update(latest);
            } catch (Throwable ignored) {}
        });
    }

    public static String downloadCover(Context context, String imageUrl, String prefix) {
        return downloadAndSaveCover(context, imageUrl, prefix);
    }

    private static String downloadAndSaveCover(Context context, String imageUrl, String prefix) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return null;
        InputStream is = null;
        try {
            File dir = new File(context.getFilesDir(), "covers_remote");
            if (!dir.exists()) dir.mkdirs();
            String name = prefix + "_" + Math.abs(imageUrl.hashCode()) + ".jpg";
            File cacheFile = new File(dir, name);
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return Uri.fromFile(cacheFile).toString();
            }
            URL url = new URL(imageUrl.trim());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Referer", "https://vndb.org/");
            conn.setRequestProperty("Cookie", "vndb_img=1; vndb_samesite=1");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_SEE_OTHER) {
                String next = conn.getHeaderField("Location");
                conn.disconnect();
                if (next != null && !next.isEmpty()) return downloadAndSaveCover(context, next, prefix);
                return null;
            }
            is = conn.getInputStream();
            byte[] buffer = new byte[8192];
            int len;
            FileOutputStream fos = new FileOutputStream(cacheFile);
            while ((len = is.read(buffer)) != -1) fos.write(buffer, 0, len);
            fos.close();
            is.close();
            is = null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(cacheFile.getAbsolutePath(), opts);
            if (opts.outWidth <= 0) { cacheFile.delete(); return null; }
            int maxPx = 720;
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sampleSize = 1;
            while (maxDim / sampleSize > 1440) sampleSize *= 2;
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath(), decodeOpts);
            if (bitmap == null) { cacheFile.delete(); return null; }
            float scale = Math.min(1f, (float) maxPx / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f) {
                int nw = Math.round(bitmap.getWidth() * scale);
                int nh = Math.round(bitmap.getHeight() * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
                bitmap.recycle();
                bitmap = scaled;
                FileOutputStream fos2 = new FileOutputStream(cacheFile);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos2);
                fos2.close();
            }
            bitmap.recycle();
            return Uri.fromFile(cacheFile).toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
        }
    }

    private static Game copyGame(Game src) {
        Game g = new Game();
        g.id = src.id;
        g.title = src.title;
        g.rootUri = src.rootUri;
        g.engine = src.engine;
        return g;
    }
}
