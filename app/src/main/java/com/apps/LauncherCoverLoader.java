package com.apps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.yuki.yukihub.util.AppExecutors;

import java.io.InputStream;

/**
 * 封面异步加载器：在 IO 线程解码 + 内存缓存 + 采样降采样，
 * 避免 RecyclerView 绑定期间在主线程同步解码导致的掉帧。
 */
public final class LauncherCoverLoader {
    private LauncherCoverLoader() {}

    /** 内存缓存：取应用可用内存的 1/8，至少 2MB */
    private static final int CACHE_SIZE = Math.max(2 * 1024 * 1024,
            (int) (Runtime.getRuntime().maxMemory() / 8));
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 封面卡片目标尺寸（2 列网格下约 160dp × 144dp，按 3x 密度估算） */
    private static final int TARGET_WIDTH = 480;
    private static final int TARGET_HEIGHT = 432;

    public interface Callback {
        void onLoaded(boolean success);
    }

    public static void loadInto(ImageView imageView, String uriText, Callback callback) {
        if (imageView == null) return;
        final String key = uriText == null ? "" : uriText.trim();
        // 用 tag 记录当前请求，避免回收/复用后旧请求覆盖新内容
        final String prev = (String) imageView.getTag();
        if (key.equals(prev)) return;
        imageView.setTag(key);
        imageView.setImageDrawable(null);

        if (key.isEmpty()) {
            if (callback != null) callback.onLoaded(false);
            return;
        }

        Bitmap cached = cache.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            if (callback != null) callback.onLoaded(true);
            return;
        }

        final Context ctx = imageView.getContext().getApplicationContext();
        AppExecutors.runOnIo(() -> {
            final Bitmap bitmap = decodeSampled(ctx, key);
            if (bitmap != null) cache.put(key, bitmap);
            mainHandler.post(() -> {
                if (!key.equals(imageView.getTag())) return;
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                }
                if (callback != null) callback.onLoaded(bitmap != null);
            });
        });
    }

    /** 取消该 ImageView 上的待加载请求并清空显示 */
    public static void clear(ImageView imageView) {
        if (imageView == null) return;
        imageView.setTag(null);
        imageView.setImageDrawable(null);
    }

    private static Bitmap decodeSampled(Context ctx, String uriText) {
        try {
            Uri uri = Uri.parse(uriText);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight);
            opts.inJustDecodeBounds = false;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int calculateSampleSize(int width, int height) {
        if (width <= 0 || height <= 0) return 1;
        int sample = 1;
        while ((width / sample) > TARGET_WIDTH || (height / sample) > TARGET_HEIGHT) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }
}
