package com.apps.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;
import com.apps.LauncherPreferences;
import com.core.launcher.EnginePackages;
import com.core.launcherbridge.LauncherAuthBridge;
import com.core.launcherbridge.LauncherRepositoryBridge;
import com.core.launcherbridge.LauncherRepositoryBridge.RecentActivity;
import com.core.launcherbridge.LauncherSyncBridge;
import com.core.model.Game;
import com.core.util.TimeFormatUtil;
import com.core.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LauncherRepository {
    /** Maximum number of non-recycled favorite cards rendered by SquareGrid Home. */
    public static final int FAVORITE_ITEM_LIMIT = 30;
    private static final int RECENT_ITEM_LIMIT = 18;
    private static final int RECENT_TITLE_MAX_CODE_POINTS = 19;
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
        appPrefs = appContext.getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE);
    }

    public LauncherSnapshot loadSnapshot() {
        return loadSnapshot(false);
    }

    public LauncherSnapshot loadSnapshot(boolean includeFavorites) {
        List<Game> games = LauncherRepositoryBridge.getAllGames(appContext);
        StatsSnapshot stats = loadStatsSnapshot(games);
        List<FavoriteItem> favoriteItems = includeFavorites
                ? loadFavoriteItems(games)
                : new ArrayList<>();
        return new LauncherSnapshot(
                stats.accountName,
                stats.accountMode,
                stats.syncStatus,
                stats.gameCount,
                stats.totalPlayTime,
                stats.todayPlayTime,
                favoriteItems,
                loadRecentItems()
        );
    }

    public HomeListsSnapshot loadHomeListsSnapshot() {
        List<Game> games = LauncherRepositoryBridge.getAllGames(appContext);
        return new HomeListsSnapshot(loadFavoriteItems(games), loadRecentItems());
    }

    private List<FavoriteItem> loadFavoriteItems(List<Game> games) {
        List<FavoriteItem> favoriteItems = new ArrayList<>();
        for (Game game : games) {
            if (game == null || !game.favorite) continue;
            String fullTitle = game.title == null || game.title.trim().isEmpty()
                    ? textContext().getString(R.string.pad_untitled_game)
                    : game.title.trim();
            String coverUri = game.coverPersistUri != null && !game.coverPersistUri.trim().isEmpty()
                    ? game.coverPersistUri : game.coverUri;
            favoriteItems.add(new FavoriteItem(
                    ellipsizeByCodePoint(fullTitle, RECENT_TITLE_MAX_CODE_POINTS),
                    TimeFormatUtil.playTime(Math.max(0L, game.totalPlayTime)),
                    firstTitleChar(fullTitle),
                    coverUri,
                    game.id
            ));
            if (favoriteItems.size() >= FAVORITE_ITEM_LIMIT) break;
        }
        return favoriteItems;
    }

    public StatsSnapshot loadStatsSnapshot() {
        return loadStatsSnapshot(LauncherRepositoryBridge.getAllGames(appContext));
    }

    private StatsSnapshot loadStatsSnapshot(List<Game> games) {
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
        List<RecentActivity> activities = LauncherRepositoryBridge.getRecentPlayActivities(appContext, RECENT_ITEM_LIMIT);
        List<Long> gameIds = new ArrayList<>(activities.size());
        for (RecentActivity activity : activities) {
            if (activity != null && activity.gameId > 0L) gameIds.add(activity.gameId);
        }
        Map<Long, Game> gamesById = new HashMap<>();
        for (Game game : LauncherRepositoryBridge.findGamesByIds(appContext, gameIds)) {
            if (game != null && game.id > 0L) gamesById.put(game.id, game);
        }
        List<RecentItem> recentItems = new ArrayList<>();
        for (RecentActivity activity : activities) {
            if (activity != null) recentItems.add(toRecentItem(activity, gamesById.get(activity.gameId)));
        }
        return recentItems;
    }

    private RecentItem toRecentItem(RecentActivity activity, Game game) {
        String fullTitle = activity.gameTitle == null || activity.gameTitle.trim().isEmpty()
                ? textContext().getString(R.string.pad_untitled_game)
                : activity.gameTitle.trim();
        String title = ellipsizeByCodePoint(fullTitle, RECENT_TITLE_MAX_CODE_POINTS);
        String dateTime = formatRecentTime(activity.endTime);
        String duration = TimeFormatUtil.playTime(activity.duration);
        String time = dateTime + " · " + duration;
        String status = launchTypeLabel(appContext, activity.launchType);
        if (status.isEmpty()) status = textContext().getString(R.string.repo_played);
        String coverUri = game == null ? "" : (game.coverPersistUri != null && !game.coverPersistUri.trim().isEmpty()
                ? game.coverPersistUri : game.coverUri);
        return new RecentItem(
                title,
                time,
                dateTime,
                duration,
                status,
                firstTitleChar(fullTitle),
                coverUri,
                activity.gameId,
                activity.sessionId,
                activity.launchType
        );
    }

    private String displayName() {
        if (LauncherAuthBridge.isLoggedIn(appContext)) {
            String nickname = LauncherAuthBridge.getNickname(appContext);
            if (nickname != null && !nickname.trim().isEmpty()) return nickname.trim();
        }
        String profileName = appPrefs.getString(KEY_PROFILE_NAME, "");
        if (profileName != null && !profileName.trim().isEmpty()) return profileName.trim();
        return textContext().getString(R.string.home_local_player);
    }

    private String accountMode() {
        String status = appPrefs.getString(KEY_AUTH_STATUS, "");
        Context textContext = textContext();
        if (AUTH_STATUS_EXPIRED.equals(status)) return textContext.getString(R.string.repo_local_expired);
        if (!LauncherAuthBridge.isLoggedIn(appContext)) return textContext.getString(R.string.home_local_mode);
        if (AUTH_STATUS_ONLINE.equals(status)) return textContext.getString(R.string.pad_online_mode);
        if (AUTH_STATUS_SYNCING.equals(status)) return textContext.getString(R.string.pad_online_syncing);
        return textContext.getString(R.string.pad_online_mode);
    }

    private String syncStatus() {
        Context textContext = textContext();
        if (!LauncherSyncBridge.isConfigured(appContext)) return textContext.getString(R.string.repo_webdav_not_configured);
        StringBuilder builder = new StringBuilder(textContext.getString(R.string.repo_webdav_configured));
        builder.append(textContext.getString(LauncherSyncBridge.isAutoSyncEnabled(appContext)
                ? R.string.repo_auto_sync_on : R.string.repo_auto_sync_off));
        long lastSync = LauncherSyncBridge.lastSyncTime(appContext);
        if (lastSync > 0) {
            builder.append(textContext.getString(R.string.repo_last_sync, formatSyncTime(lastSync)));
        } else {
            builder.append(textContext.getString(R.string.repo_never_synced));
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
        if (time <= 0L) return textContext().getString(R.string.repo_never_recorded);
        return TimeFormatUtil.shortDate(time);
    }

    private String formatSyncTime(long time) {
        return TimeFormatUtil.shortDate(time);
    }

    public static String launchTypeLabel(Context context, String launchType) {
        if (launchType == null || launchType.trim().isEmpty()) return "";
        String value = launchType.trim();
        if (value.startsWith(EnginePackages.INTERNAL_KRKR)) return context.getString(R.string.repo_internal_krkr);
        if (value.startsWith(EnginePackages.INTERNAL_ONS)) return context.getString(R.string.repo_internal_ons);
        if (value.startsWith(EnginePackages.INTERNAL_TYRANO)) return context.getString(R.string.repo_internal_tyrano);
        if (value.startsWith(EnginePackages.INTERNAL_ARTEMIS)) return context.getString(R.string.repo_internal_artemis);
        if (value.startsWith("internal.")) return context.getString(R.string.repo_internal_launch);
        if ("manual".equals(value)) return context.getString(R.string.repo_manual_record);
        if ("external".equals(value)) return context.getString(R.string.repo_external_emulator);
        return value;
    }

    private String firstTitleChar(String title) {
        if (title == null) return textContext().getString(R.string.pad_game_fallback_initial);
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return textContext().getString(R.string.pad_game_fallback_initial);
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

    /**
     * AppCompat stores its selected locale separately on pre-Android 13. The Application
     * resources held by this long-lived repository can therefore lag behind a language change.
     * Resolve display strings through a fresh configuration context on every snapshot load.
     */
    private Context textContext() {
        try {
            androidx.core.os.LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
            if (locales.isEmpty() || locales.get(0) == null) return appContext;
            Configuration configuration = new Configuration(appContext.getResources().getConfiguration());
            configuration.setLocale(locales.get(0));
            return appContext.createConfigurationContext(configuration);
        } catch (RuntimeException ignored) {
            // 本地化配置创建失败时回退 appContext（未应用新语言，展示仍可读），可安全忽略
            return appContext;
        }
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
        public final List<FavoriteItem> favoriteItems;
        public final List<RecentItem> recentItems;

        LauncherSnapshot(
                String accountName,
                String accountMode,
                String syncStatus,
                int gameCount,
                String totalPlayTime,
                String todayPlayTime,
                List<FavoriteItem> favoriteItems,
                List<RecentItem> recentItems
        ) {
            super(accountName, accountMode, syncStatus, gameCount, totalPlayTime, todayPlayTime);
            this.favoriteItems = favoriteItems;
            this.recentItems = recentItems;
        }
    }

    static final class HomeListsSnapshot {
        public final List<FavoriteItem> favoriteItems;
        public final List<RecentItem> recentItems;

        HomeListsSnapshot(List<FavoriteItem> favoriteItems, List<RecentItem> recentItems) {
            this.favoriteItems = favoriteItems;
            this.recentItems = recentItems;
        }
    }

    public static final class FavoriteItem {
        public final String title;
        public final String playTime;
        public final String iconText;
        public final String coverUri;
        public final long gameId;

        FavoriteItem(String title, String playTime, String iconText, String coverUri, long gameId) {
            this.title = title;
            this.playTime = playTime;
            this.iconText = iconText;
            this.coverUri = coverUri;
            this.gameId = gameId;
        }
    }

    public static final class RecentItem {
        public final String title;
        public final String timeAndDuration;
        public final String dateTime;
        public final String duration;
        public final String status;
        public final String iconText;
        public final String coverUri;
        public final long gameId;
        public final long sessionId;
        public final String launchType;

        RecentItem(
                String title,
                String timeAndDuration,
                String dateTime,
                String duration,
                String status,
                String iconText,
                String coverUri,
                long gameId,
                long sessionId,
                String launchType
        ) {
            this.title = title;
            this.timeAndDuration = timeAndDuration;
            this.dateTime = dateTime;
            this.duration = duration;
            this.status = status;
            this.iconText = iconText;
            this.coverUri = coverUri;
            this.gameId = gameId;
            this.sessionId = sessionId;
            this.launchType = launchType;
        }
    }
}
