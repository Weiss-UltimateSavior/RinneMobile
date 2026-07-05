package com.apps;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

import com.yuki.yukihub.R;

final class LauncherMotion {
    private static final long TONE_OVERLAY_MS = 180L;

    private LauncherMotion() {
    }

    static void applyDialogMotion(Dialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(R.style.LauncherDialogAnimation);
        }
    }

    static void applyActivityOpen(Activity activity) {
        if (activity != null) {
            activity.overridePendingTransition(R.anim.launcher_activity_enter, R.anim.launcher_activity_exit);
        }
    }

    static void applyActivityClose(Activity activity) {
        if (activity != null) {
            activity.overridePendingTransition(R.anim.launcher_activity_pop_enter, R.anim.launcher_activity_pop_exit);
        }
    }

    static void finish(Activity activity) {
        if (activity == null) return;
        activity.finish();
        applyActivityClose(activity);
    }

    static void pulse(View view) {
        if (view == null) return;
        view.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .setDuration(110L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(130L)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start())
                .start();
    }

    static void recreateWithToneOverlay(Activity activity, Runnable beforeRecreate) {
        if (activity == null) return;
        Window window = activity.getWindow();
        ViewGroup decor = window == null ? null : (ViewGroup) window.getDecorView();
        if (decor == null) {
            if (beforeRecreate != null) beforeRecreate.run();
            activity.recreate();
            activity.overridePendingTransition(R.anim.launcher_tone_enter, R.anim.launcher_tone_exit);
            return;
        }

        View overlay = new View(activity);
        overlay.setBackgroundColor(overlayColor(activity));
        overlay.setAlpha(0f);
        decor.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.animate()
                .alpha(1f)
                .setDuration(TONE_OVERLAY_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (beforeRecreate != null) beforeRecreate.run();
                    activity.recreate();
                    activity.overridePendingTransition(R.anim.launcher_tone_enter, R.anim.launcher_tone_exit);
                })
                .start();
    }

    static void runAfterPulse(@Nullable View view, Runnable action) {
        pulse(view);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (action != null) action.run();
        }, 150L);
    }

    private static int overlayColor(Activity activity) {
        int primary = LauncherTheme.primary(activity);
        int bg = LauncherTheme.card(activity);
        return Color.rgb(
                Math.round(Color.red(primary) * 0.28f + Color.red(bg) * 0.72f),
                Math.round(Color.green(primary) * 0.28f + Color.green(bg) * 0.72f),
                Math.round(Color.blue(primary) * 0.28f + Color.blue(bg) * 0.72f)
        );
    }
}
