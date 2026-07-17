package com.yuki.yukihub.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SafeImageLoader {
    public interface Callback { void onResult(boolean success); }
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static final WeakHashMap<ImageView, Long> ACTIVE_REQUESTS = new WeakHashMap<>();
    private SafeImageLoader() {
    }

    public static boolean loadUri(ImageView imageView, String uriText, Callback callback) {
        if (imageView == null) return false;
        final long requestId = REQUEST_IDS.incrementAndGet();
        synchronized (ACTIVE_REQUESTS) { ACTIVE_REQUESTS.put(imageView, requestId); }
        imageView.setImageDrawable(null);
        if (uriText == null || uriText.trim().isEmpty()) return false;
        final android.content.Context context = imageView.getContext().getApplicationContext();
        final Uri uri;
        try { uri = Uri.parse(uriText.trim()); }
        catch (Throwable ignored) { return false; }
        AppExecutors.runOnIo(() -> {
            Bitmap bitmap = decodeSampled(context, uri, imageView.getWidth(), imageView.getHeight());
            RxMainScheduler.post(() -> {
                boolean current;
                synchronized (ACTIVE_REQUESTS) {
                    Long active = ACTIVE_REQUESTS.get(imageView);
                    current = active != null && active == requestId;
                    if (current) ACTIVE_REQUESTS.remove(imageView);
                }
                if (!current) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap != null) imageView.setImageBitmap(bitmap); else imageView.setImageDrawable(null);
                if (callback != null) callback.onResult(bitmap != null);
            });
        });
        return true;
    }

    private static Bitmap decodeSampled(android.content.Context context, Uri uri, int requestedWidth, int requestedHeight) {
        int targetWidth = requestedWidth > 0 ? requestedWidth : 512;
        int targetHeight = requestedHeight > 0 ? requestedHeight : 512;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
            if (pixels > 100_000_000L) return null;
            int sample = 1;
            while (bounds.outWidth / sample > targetWidth * 2 || bounds.outHeight / sample > targetHeight * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
