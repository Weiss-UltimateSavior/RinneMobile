package com.yuki.yukihub.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;

import java.io.InputStream;

public final class SafeImageLoader {
    private SafeImageLoader() {
    }

    public static boolean loadUri(ImageView imageView, String uriText) {
        if (imageView == null) return false;
        imageView.setImageDrawable(null);
        if (uriText == null || uriText.trim().isEmpty()) return false;
        try (InputStream inputStream = imageView.getContext()
                .getContentResolver()
                .openInputStream(Uri.parse(uriText.trim()))) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) return false;
            imageView.setImageBitmap(bitmap);
            return true;
        } catch (Throwable ignored) {
            imageView.setImageDrawable(null);
            return false;
        }
    }
}
