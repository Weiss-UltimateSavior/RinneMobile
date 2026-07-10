package com.apps;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

import com.yuki.yukihub.R;

public final class LauncherMotion {
    private LauncherMotion() {
    }

    public static void applyDialogMotion(Dialog dialog) {
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

    public static void pulse(View view) {
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

    /**
     * 切换色调模式：在新窗口淡入的同时渲染新主题页面，旧窗口保持不透明，
     * 避免黑底穿透。动画进行中即完成页面重建。
     */
    public static void recreateWithToneOverlay(Activity activity, Runnable beforeRecreate) {
        if (activity == null) return;
        if (beforeRecreate != null) beforeRecreate.run();
        Intent intent = new Intent(activity, activity.getClass());
        activity.startActivity(intent);
        activity.finish();
        activity.overridePendingTransition(R.anim.launcher_tone_enter, R.anim.launcher_tone_exit);
    }

    public static void runAfterPulse(@Nullable View view, Runnable action) {
        pulse(view);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (action != null) action.run();
        }, 150L);
    }
}
