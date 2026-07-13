package com.yuki.yukihub.launcherbridge;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/** Reads GameHub desktop shortcut metadata through the user-authorized Shizuku service. */
public final class LauncherGameHubShortcutBridge {
    private LauncherGameHubShortcutBridge() {
    }

    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void requestShizukuPermission(int requestCode) {
        Shizuku.requestPermission(requestCode);
    }

    /**
     * Returns GameHub shortcuts containing a launchable localGameId. This only reads
     * shortcut metadata; it does not modify GameHub or execute any game command.
     */
    public static List<Shortcut> loadShortcuts() throws Exception {
        if (!isShizukuRunning() || !hasShizukuPermission()) return new ArrayList<>();
        String output = runShizukuCommand(
                "uid=$(am get-current-user 2>/dev/null); case \"$uid\" in ''|*[!0-9]*) uid=0;; esac; "
                        + "for u in $uid 0; do "
                        + "cmd shortcut get-shortcuts --user $u --flags 31 com.xiaoji.egggamz 2>&1; "
                        + "cmd shortcut get-shortcuts --user $u --flags 31 com.xiaoji.egggame 2>&1; "
                        + "done; dumpsys shortcut 2>&1 | grep -i -A 40 -B 12 'com.xiaoji.egggamz\\|com.xiaoji.egggame\\|localGameId\\|local_\\|steamAppId' 2>&1");

        List<Shortcut> items = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        String text = output == null ? "" : output.replace('\0', ' ');
        for (String line : text.split("\\r?\\n")) addIfValid(parseShortcut(line), items, seen);
        Matcher matcher = Pattern.compile("(?i)(localGameId\\s*[:=]\\s*([^,}\\]\\s]+)|\\blocal_[0-9a-f-]{8,}\\b|steamAppI[dD]\\s*[:=]\\s*[^0-9]*([0-9]+))").matcher(text);
        while (matcher.find()) {
            int start = Math.max(0, matcher.start() - 700);
            int end = Math.min(text.length(), matcher.end() + 1600);
            addIfValid(parseShortcut(text.substring(start, end)), items, seen);
        }
        items.sort((a, b) -> a.displayLabel.compareToIgnoreCase(b.displayLabel));
        return items;
    }

    private static Shortcut parseShortcut(String text) {
        if (text == null) return null;
        String localGameId = matchFirst(text, "(?i)\\blocalGameId\\b\\s*[:=]\\s*([^,}\\]\\s]+)");
        if (localGameId == null) localGameId = matchFirst(text, "(?i)\\b(local_[0-9a-f-]{8,})\\b");
        String steamAppId = matchFirst(text, "(?i)\\bsteamAppI[dD]\\b\\s*[:=]\\s*[^0-9]*([0-9]+)");
        String storedId = cleanValue(localGameId);
        if ((storedId == null || storedId.isEmpty()) && steamAppId != null && !steamAppId.trim().isEmpty()) {
            storedId = "steam:" + steamAppId.trim();
        }
        if (storedId == null || storedId.isEmpty()) return null;
        String appName = cleanValue(matchFirst(text, "(?i)\\blocalAppName\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)"));
        if (appName == null || appName.isEmpty()) appName = storedId;
        String label = cleanValue(matchFirst(text, "(?i)\\b(shortLabel|longLabel|title|name)\\b\\s*[:=]\\s*([^,}\\]\\r\\n]+)"));
        if (label == null || label.isEmpty()) label = appName;
        return new Shortcut(label, appName, storedId);
    }

    private static void addIfValid(Shortcut item, List<Shortcut> items, HashSet<String> seen) {
        if (item == null || item.localGameId.isEmpty()) return;
        if (seen.add(item.localGameId.toLowerCase(Locale.ROOT))) items.add(item);
    }

    private static String matchFirst(String text, String expression) {
        try {
            Matcher matcher = Pattern.compile(expression).matcher(text);
            return matcher.find() ? matcher.group(matcher.groupCount()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String cleanValue(String value) {
        if (value == null) return null;
        String cleaned = value.trim().replaceAll("^[\\\"'=]+|[\\\"',;]+$", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String runShizukuCommand(String command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Process process = (Process) method.invoke(null, new Object[]{new String[]{"/system/bin/sh", "-c", command}, null, null});
        String output = readProcessStream(process.getInputStream()) + "\n" + readProcessStream(process.getErrorStream());
        try {
            process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return output;
    }

    private static String readProcessStream(InputStream input) {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static final class Shortcut {
        public final String displayLabel;
        public final String localAppName;
        public final String localGameId;

        Shortcut(String displayLabel, String localAppName, String localGameId) {
            this.displayLabel = displayLabel == null ? "" : displayLabel;
            this.localAppName = localAppName == null ? "" : localAppName;
            this.localGameId = localGameId == null ? "" : localGameId;
        }
    }
}
