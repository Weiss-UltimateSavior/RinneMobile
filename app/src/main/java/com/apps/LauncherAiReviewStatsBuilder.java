package com.apps;

import android.content.Context;

import com.yuki.yukihub.ai.WeeklyPlayStats;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.GameRepository.PlayActivity;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LauncherAiReviewStatsBuilder {
    private LauncherAiReviewStatsBuilder() {
    }

    static WeeklyPlayStats build(Context context) {
        WeeklyPlayStats stats = new WeeklyPlayStats();
        if (context == null) return stats;

        GameRepository repository = new GameRepository(context.getApplicationContext());
        long end = System.currentTimeMillis();
        Calendar startCal = Calendar.getInstance();
        startCal.setTimeInMillis(end);
        startCal.add(Calendar.DAY_OF_YEAR, -7);
        long start = startCal.getTimeInMillis();
        stats.startTime = start;
        stats.endTime = end;

        Map<String, Long> durations = repository.getPlayDurationsBetween(start, end);
        List<Map.Entry<String, Long>> entries = new ArrayList<>(durations.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(valueOf(b), valueOf(a)));
        for (Map.Entry<String, Long> entry : entries) {
            long duration = valueOf(entry);
            if (duration <= 0L) continue;
            stats.totalDuration += duration;
            if (stats.topGames.size() < 8) stats.topGames.put(entry.getKey(), duration);
        }

        List<Game> games = repository.getAll();
        stats.totalGameCount = games.size();
        for (Game game : games) {
            String status = game == null || game.playStatus == null ? "" : game.playStatus.trim();
            if ("completed".equals(status)) stats.completedGameCount++;
            else if ("playing".equals(status)) stats.playingGameCount++;
            else stats.unplayedGameCount++;
        }

        List<PlayActivity> sessions = repository.getPlayActivitiesBetween(start, end, 1000);
        Set<String> activeDays = new HashSet<>();
        Calendar cursor = Calendar.getInstance();
        for (PlayActivity activity : sessions) {
            if (activity == null) continue;
            stats.sessionCount++;
            if (stats.recentSessions.size() < 8) stats.recentSessions.add(activity);
            String title = safeTitle(activity.gameTitle);
            Integer oldCount = stats.gameSessionCounts.get(title);
            stats.gameSessionCounts.put(title, oldCount == null ? 1 : oldCount + 1);
            if (!stats.gameStatuses.containsKey(title)) {
                stats.gameStatuses.put(title, statusLabel(activity.playStatus));
            }
            if (activity.duration > stats.longestSessionDuration) {
                stats.longestSessionDuration = activity.duration;
                stats.longestSessionGame = title;
            }
            cursor.setTimeInMillis(activity.endTime > 0 ? activity.endTime : activity.startTime);
            activeDays.add(cursor.get(Calendar.YEAR) + "-" + cursor.get(Calendar.DAY_OF_YEAR));
            int hour = cursor.get(Calendar.HOUR_OF_DAY);
            if (hour >= 22 || hour < 4) stats.nightCount++;
            else if (hour >= 13 && hour < 19) stats.afternoonCount++;
            else if (hour >= 8 && hour < 12) stats.morningCount++;
            else stats.otherTimeCount++;
            int day = cursor.get(Calendar.DAY_OF_WEEK);
            if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) stats.weekendCount++;
            else stats.weekdayCount++;
        }
        stats.activeDays = activeDays.size();
        stats.averageSessionDuration = stats.sessionCount > 0 ? stats.totalDuration / stats.sessionCount : 0L;
        return stats;
    }

    private static long valueOf(Map.Entry<String, Long> entry) {
        return entry == null || entry.getValue() == null ? 0L : entry.getValue();
    }

    private static String safeTitle(String title) {
        return title == null || title.trim().isEmpty() ? "未命名游戏" : title.trim();
    }

    private static String statusLabel(String status) {
        if ("completed".equals(status)) return "玩过";
        if ("playing".equals(status)) return "在玩";
        if ("planned".equals(status)) return "想玩";
        return "未玩";
    }
}
