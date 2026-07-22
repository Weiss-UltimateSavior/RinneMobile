package com.apps.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.yuki.yukihub.launcherbridge.LauncherAuthBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge;
import com.yuki.yukihub.launcherbridge.LauncherRepositoryBridge.RecentActivity;
import com.yuki.yukihub.launcherbridge.LauncherSyncBridge;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LauncherRepository {
    private static final String APP_PREFS = "yukihub_prefs";
    private static final int RECENT_ITEM_LIMIT = 5;
    private static final int RECENT_TITLE_MAX_CODE_POINTS = 10;
    private static final String KEY_PROFILE_NAME = "profile_name";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_AUTH_NICKNAME = "auth_nickname";
    private static final String KEY_AUTH_STATUS = "auth_status";
    private static final String AUTH_STATUS_ONLINE = "online";
    private static final String AUTH_STATUS_SYNCING = "syncing";
    private static final String AUTH_STATUS_EXPIRED = "expired";

    private final Context appContext;
    private final SharedPreferences appPrefs;

    public LauncherRepository(Context context) {
        appContext = context.getApplicationContext();
        appPrefs = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
    }

    public LauncherSnapshot loadSnapshot() {
        StatsSnapshot stats = loadStatsSnapshot();
        return new LauncherSnapshot(
                stats.accountName,
                stats.accountMode,
                stats.syncStatus,
                stats.gameCount,
                stats.totalPlayTime,
                stats.todayPlayTime,
                loadRecentItems()
        );
    }

    public StatsSnapshot loadStatsSnapshot() {
        List<Game> games = LauncherRepositoryBridge.getAllGames(appContext);
        long totalPlayTime = 0L;
        for (Game game : games) {
            if (game != null) totalPlayTime += Math.max(0L, game.totalPlayTime);
        }

        long todayStart = startOfToday();
        long todayEnd = todayStart + 24L * 60L * 60L * 1000L;
        long todayPlayTime = 0L;
        Map<String, Long> todayDurations = LauncherRepositoryBridge.getPlayDurationsBetween(appContext, todayStart, todayEnd);
        for (Long duration : todayDurations.values()) {
            if (duration != null) todayPlayTime += Math.max(0L, duration);
        }

        return new StatsSnapshot(
                displayName(),
                accountMode(),
                syncStatus(),
                games.size(),
                TimeFormatUtil.playTime(totalPlayTime),
                TimeFormatUtil.playTime(todayPlayTime)
        );
    }

    public List<RecentItem> loadRecentItems() {
        List<RecentItem> recentItems = new ArrayList<>();
        for (RecentActivity activity : LauncherRepositoryBridge.getRecentPlayActivities(appContext, RECENT_ITEM_LIMIT)) {
            recentItems.add(toRecentItem(activity));
        }
        return recentItems;
    }

    private RecentItem toRecentItem(RecentActivity activity) {
        String fullTitle = activity.gameTitle == null || activity.gameTitle.trim().isEmpty()
                ? "未命名游戏"
                : activity.gameTitle.trim();
        String title = ellipsizeByCodePoint(fullTitle, RECENT_TITLE_MAX_CODE_POINTS);
        String time = formatRecentTime(activity.endTime) + " · " + TimeFormatUtil.playTime(activity.duration);
        String status = launchTypeLabel(activity.launchType);
        if (status.isEmpty()) status = "已游玩";
        return new RecentItem(title, time, status, firstTitleChar(fullTitle), activity.gameId, activity.sessionId);
    }

    private String displayName() {
        if (LauncherAuthBridge.isLoggedIn(appContext)) {
            String nickname = LauncherAuthBridge.getNickname(appContext);
            if (nickname != null && !nickname.trim().isEmpty()) return nickname.trim();
        }
        String profileName = appPrefs.getString(KEY_PROFILE_NAME, "");
        if (profileName != null && !profileName.trim().isEmpty()) return profileName.trim();
        return "本地玩家";
    }

    private String accountMode() {
        String status = appPrefs.getString(KEY_AUTH_STATUS, "");
        if (AUTH_STATUS_EXPIRED.equals(status)) return "本地模式 · 登录过期";
        if (!LauncherAuthBridge.isLoggedIn(appContext)) return "本地模式";
        if (AUTH_STATUS_ONLINE.equals(status)) return "在线模式";
        if (AUTH_STATUS_SYNCING.equals(status)) return "在线模式 · 同步中";
        return "在线模式";
    }

    private String syncStatus() {
        if (!LauncherSyncBridge.isConfigured(appContext)) return "WebDAV 未配置";
        StringBuilder builder = new StringBuilder("WebDAV 已配置");
        builder.append(LauncherSyncBridge.isAutoSyncEnabled(appContext) ? " · 自动同步开启" : " · 自动同步关闭");
        long lastSync = LauncherSyncBridge.lastSyncTime(appContext);
        if (lastSync > 0) {
            builder.append(" · 上次同步 ").append(formatSyncTime(lastSync));
        } else {
            builder.append(" · 尚未同步");
        }
        return builder.toString();
    }

    private long startOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private String formatRecentTime(long time) {
        if (time <= 0L) return "从未记录";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(time);
    }

    private String formatSyncTime(long time) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(time);
    }

    private String launchTypeLabel(String launchType) {
        if (launchType == null || launchType.trim().isEmpty()) return "";
        String value = launchType.trim();
        if (value.startsWith("internal.krkr")) return "内置 KRKR";
        if (value.startsWith("internal.ons")) return "内置 ONS";
        if (value.startsWith("internal.tyrano")) return "内置 Tyrano";
        if (value.startsWith("internal.artemis")) return "内置 Artemis";
        if (value.startsWith("internal.")) return "内置启动";
        if ("manual".equals(value)) return "手动记录";
        if ("external".equals(value)) return "外部模拟器";
        return value;
    }

    private String firstTitleChar(String title) {
        if (title == null) return "游";
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return "游";
        int end = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, end);
    }

    private String ellipsizeByCodePoint(String value, int maxCodePoints) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty() || maxCodePoints <= 0) return trimmed;
        if (trimmed.codePointCount(0, trimmed.length()) <= maxCodePoints) return trimmed;
        int end = trimmed.offsetByCodePoints(0, maxCodePoints);
        return trimmed.substring(0, end) + "...";
    }

    public static class StatsSnapshot {
        public final String accountName;
        public final String accountMode;
        public final String syncStatus;
        public final int gameCount;
        public final String totalPlayTime;
        public final String todayPlayTime;

        StatsSnapshot(
                String accountName,
                String accountMode,
                String syncStatus,
                int gameCount,
                String totalPlayTime,
                String todayPlayTime
        ) {
            this.accountName = accountName;
            this.accountMode = accountMode;
            this.syncStatus = syncStatus;
            this.gameCount = gameCount;
            this.totalPlayTime = totalPlayTime;
            this.todayPlayTime = todayPlayTime;
        }
    }

    public static final class LauncherSnapshot extends StatsSnapshot {
        public final List<RecentItem> recentItems;

        LauncherSnapshot(
                String accountName,
                String accountMode,
                String syncStatus,
                int gameCount,
                String totalPlayTime,
                String todayPlayTime,
                List<RecentItem> recentItems
        ) {
            super(accountName, accountMode, syncStatus, gameCount, totalPlayTime, todayPlayTime);
            this.recentItems = recentItems;
        }
    }

    public static final class RecentItem {
        public final String title;
        public final String timeAndDuration;
        public final String status;
        public final String iconText;
        public final long gameId;
        public final long sessionId;

        RecentItem(String title, String timeAndDuration, String status, String iconText, long gameId, long sessionId) {
            this.title = title;
            this.timeAndDuration = timeAndDuration;
            this.status = status;
            this.iconText = iconText;
            this.gameId = gameId;
            this.sessionId = sessionId;
        }
    }
}
