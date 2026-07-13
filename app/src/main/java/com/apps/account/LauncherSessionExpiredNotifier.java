package com.apps.account;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.apps.LauncherActivity;
import com.apps.theme.LauncherMotion;
import com.apps.theme.LauncherTheme;
import com.yuki.yukihub.R;
import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;

import java.lang.ref.WeakReference;

/** Shows one actionable session-expiry prompt for whichever Launcher page is foreground. */
public final class LauncherSessionExpiredNotifier implements LauncherAuthBridge.SessionExpiredListener {
    private static final LauncherSessionExpiredNotifier INSTANCE = new LauncherSessionExpiredNotifier();
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private boolean promptVisible;

    private LauncherSessionExpiredNotifier() { }

    public static void install(Application application) {
        LauncherAuthBridge.setSessionExpiredListener(INSTANCE);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) { INSTANCE.resumedActivity = new WeakReference<>(activity); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) {
                Activity current = INSTANCE.resumedActivity.get();
                if (current == activity) INSTANCE.resumedActivity = new WeakReference<>(null);
            }
        });
    }

    @Override public void onSessionExpired() {
        Activity activity = resumedActivity.get();
        if (promptVisible || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        promptVisible = true;
        showDialog(activity);
    }

    @Override public void onSessionRestored() { promptVisible = false; }

    private void showDialog(Activity activity) {
        AlertDialog dialog = new AlertDialog.Builder(activity).create();
        dialog.setCancelable(true);
        dialog.setOnDismissListener(ignored -> promptVisible = false);
        dialog.show();
        LauncherMotion.applyDialogMotion(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(dp(activity, 276), WindowManager.LayoutParams.WRAP_CONTENT);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 16));
        root.setBackgroundResource(R.drawable.launcher_dialog_bg);

        TextView title = text(activity, "登录已过期", 16, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView message = text(activity, "当前账号的登录状态已失效。重新登录后即可继续使用聊天、云同步和在线游玩统计。", 12, false);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.setMargins(0, dp(activity, 13), 0, 0);
        root.addView(message, messageParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(activity, 36));
        actionsParams.setMargins(0, dp(activity, 16), 0, 0);
        TextView later = text(activity, "稍后", 13, true);
        LauncherTheme.secondaryButton(later);
        later.setOnClickListener(view -> dialog.dismiss());
        actions.addView(later, new LinearLayout.LayoutParams(0, -1, 1));
        TextView login = text(activity, "重新登录", 13, true);
        LauncherTheme.primaryButton(login);
        login.setOnClickListener(view -> {
            dialog.dismiss();
            Intent intent = new Intent(activity, LauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(LauncherActivity.EXTRA_OPEN_ACCOUNT_LOGIN, true);
            activity.startActivity(intent);
        });
        LinearLayout.LayoutParams loginParams = new LinearLayout.LayoutParams(0, -1, 1);
        loginParams.setMargins(dp(activity, 8), 0, 0, 0);
        actions.addView(login, loginParams);
        root.addView(actions, actionsParams);
        window.setContentView(root);
    }

    private static TextView text(Activity activity, String value, float size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(activity, bold ? R.color.launcher_text_color : R.color.launcher_text_muted_color));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
