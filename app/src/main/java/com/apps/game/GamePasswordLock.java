package com.apps.game;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

/**
 * 游戏密码锁定工具类。
 * 提供设置/取消/验证密码的统一入口，供 LauncherGameActionController 和三个 Fragment 共用。
 */
public final class GamePasswordLock {

    private GamePasswordLock() {
    }

    /** 检查游戏是否已设置密码 */
    public static boolean hasPassword(Game game) {
        return game != null && game.passwordLock != null && !game.passwordLock.trim().isEmpty();
    }

    /** 设置密码：弹出九宫格 → 两次确认 → 保存到 DB */
    public static void setPassword(Fragment fragment, Game game, Runnable onDone) {
        if (fragment == null || game == null || !fragment.isAdded()) return;
        String title = safeTitle(game);
        GamePasswordDialog.showSetDialog(fragment.requireContext(), title, hashedPassword -> {
            savePasswordToDb(fragment, game, hashedPassword, "密码已设置", onDone);
        });
    }

    /** 取消密码：验证当前密码后清除 */
    public static void clearPassword(Fragment fragment, Game game, Runnable onDone) {
        if (fragment == null || game == null || !fragment.isAdded()) return;
        String title = safeTitle(game);
        GamePasswordDialog.showVerifyDialog(fragment.requireContext(), title, game.passwordLock, () -> {
            savePasswordToDb(fragment, game, null, "密码已取消", onDone);
        });
    }

    /**
     * 启动前密码验证拦截。
     * 有密码 → 弹验证框 → 验证成功 → onLaunch.run()
     * 无密码 → 直接 onLaunch.run()
     */
    public static void interceptLaunch(Fragment fragment, Game game, Runnable onLaunch) {
        if (fragment == null || game == null || !fragment.isAdded()) return;
        if (hasPassword(game)) {
            GamePasswordDialog.showVerifyDialog(fragment.requireContext(), safeTitle(game),
                    game.passwordLock, onLaunch);
        } else {
            if (onLaunch != null) onLaunch.run();
        }
    }

    private static void savePasswordToDb(Fragment fragment, Game game, String hashedPassword,
                                          String toastMessage, Runnable onDone) {
        Context app = fragment.requireContext().getApplicationContext();
        AppExecutors.io().execute(() -> {
            boolean success = false;
            try {
                Game latest = LauncherRepositoryBridge.findGameById(app, game.id);
                if (latest != null) {
                    latest.passwordLock = hashedPassword;
                    LauncherRepositoryBridge.updateGame(app, latest);
                    game.passwordLock = hashedPassword;
                    success = true;
                }
            } catch (Throwable ignored) {
            }
            Activity activity = fragment.getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!fragment.isAdded()) return;
                Toast.makeText(fragment.requireContext(), toastMessage, Toast.LENGTH_SHORT).show();
                if (onDone != null) onDone.run();
            });
        });
    }

    private static String safeTitle(Game game) {
        if (game.title == null || game.title.trim().isEmpty()) return "未命名游戏";
        return game.title.trim();
    }
}
