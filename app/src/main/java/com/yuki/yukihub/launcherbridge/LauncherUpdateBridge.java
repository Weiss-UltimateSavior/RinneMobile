package com.yuki.yukihub.launcherbridge;

import android.content.Context;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.RxMainScheduler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动器检查更新桥接层。
 * 复用主项目的检查更新逻辑（GitHub Release API），由 Launcher 直接调用，
 * 避免跳转到 MainActivity 即可完成版本检查并展示启动器风格弹窗。
 */
public final class LauncherUpdateBridge {
    private static final String UPDATE_API_URL = "https://api.github.com/repos/Weiss-UltimateSavior/RinneMobile/releases/tags/test";
    private static final String UPDATE_REPO_URL = "https://github.com/Weiss-UltimateSavior/RinneMobile";

    private LauncherUpdateBridge() {
    }

    public static void checkUpdate(Context context, Callback callback) {
        if (context == null) {
            if (callback != null) callback.onError("上下文不可用");
            return;
        }
        AppExecutors.runOnIo(() -> {
            try {
                UpdateInfo info = fetchLatestRelease();
                String current = getCurrentVersionName(context.getApplicationContext());
                boolean newer = info != null && isNewerVersion(info.version, current);
                postOnMain(() -> {
                    if (callback != null) callback.onResult(info, current, newer);
                });
            } catch (Throwable t) {
                postOnMain(() -> {
                    if (callback != null) {
                        String msg = t.getMessage() == null || t.getMessage().trim().isEmpty()
                                ? "请稍后重试" : t.getMessage();
                        callback.onError("检查更新失败：" + msg);
                    }
                });
            }
        });
    }

    private static void postOnMain(Runnable runnable) {
        RxMainScheduler.post(runnable);
    }

    private static UpdateInfo fetchLatestRelease() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_API_URL).openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "YukiHub-Android/" + "");
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new RuntimeException("GitHub HTTP " + code + ": " + trimForDialog(text, 160));
        }
        JSONObject o = new JSONObject(text == null ? "{}" : text);
        UpdateInfo info = new UpdateInfo();
        info.tagName = o.optString("tag_name", "");
        info.version = normalizeVersion(info.tagName);
        info.name = o.optString("name", info.tagName);
        info.body = o.optString("body", "");
        info.releaseUrl = o.optString("html_url", UPDATE_REPO_URL + "/releases");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String assetName = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (url == null || url.trim().isEmpty()) continue;
                if (info.downloadUrl == null || info.downloadUrl.isEmpty()) info.downloadUrl = url;
                String lowerName = assetName.toLowerCase(Locale.ROOT);
                String lowerUrl = url.toLowerCase(Locale.ROOT);
                if (lowerName.endsWith(".apk") || lowerUrl.contains(".apk")) {
                    info.apkUrl = url;
                    break;
                }
            }
        }
        if (info.version == null || info.version.isEmpty()) info.version = normalizeVersion(info.name);
        if (info.downloadUrl == null || info.downloadUrl.isEmpty()) info.downloadUrl = info.releaseUrl;
        if (info.apkUrl == null || info.apkUrl.isEmpty()) info.apkUrl = info.releaseUrl;
        return info;
    }

    private static String getCurrentVersionName(Context context) {
        return "0.9.9.8";
    }

    private static boolean isNewerVersion(String latest, String current) {
        String l = normalizeVersion(latest);
        String c = normalizeVersion(current);
        if (l.isEmpty() || c.isEmpty()) return !l.equals(c);
        String[] la = l.split("\\.");
        String[] ca = c.split("\\.");
        int n = Math.max(la.length, ca.length);
        for (int i = 0; i < n; i++) {
            long lv = i < la.length ? parseVersionPart(la[i]) : 0L;
            long cv = i < ca.length ? parseVersionPart(ca[i]) : 0L;
            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }

    private static long parseVersionPart(String part) {
        try {
            if (part == null) return 0L;
            String digits = part.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0L : Long.parseLong(digits);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String normalizeVersion(String value) {
        if (value == null) return "";
        String v = value.trim();
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+){1,5})").matcher(v);
        if (m.find()) return m.group(1);
        v = v.replaceFirst("^[vV]", "").replaceAll("[^0-9.]", "");
        while (v.startsWith(".")) v = v.substring(1);
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String readSmallText(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0, len;
        while ((len = is.read(buf)) != -1 && total < 256 * 1024) {
            bos.write(buf, 0, len);
            total += len;
        }
        return bos.toString("UTF-8");
    }

    private static String trimForDialog(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (max <= 0 || t.length() <= max) return t;
        return t.substring(0, max) + "\n...";
    }

    public interface Callback {
        void onResult(UpdateInfo info, String currentVersion, boolean hasUpdate);
        void onError(String message);
    }

    public static final class UpdateInfo {
        public String tagName;
        public String version;
        public String name;
        public String body;
        public String releaseUrl;
        public String downloadUrl;
        public String apkUrl;
    }
}
