package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import android.content.Intent;

import com.yuki.yukihub.MainActivity;

public final class YukiHubBridge {
    private YukiHubBridge() {
    }

    public static void openAction(Context context, String action) {
        if (context == null || action == null || action.trim().isEmpty()) return;
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_LAUNCH_ACTION, action);
        context.startActivity(intent);
    }

    public static void openGameAction(Context context, String action, long gameId) {
        if (context == null || action == null || action.trim().isEmpty()) return;
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_LAUNCH_ACTION, action);
        intent.putExtra(MainActivity.EXTRA_LAUNCH_GAME_ID, gameId);
        context.startActivity(intent);
    }
}
