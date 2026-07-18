package com.apps.agent;

import java.util.ArrayList;
import java.util.List;

/** Validates model-supplied paths before they reach a game SAF tree. */
final class AgentRelativePath {
    private AgentRelativePath() { }

    static String normalize(String value, boolean allowEmpty) {
        String original = value == null ? "" : value;
        String path = original.trim();
        if (!original.equals(path)) throw new IllegalArgumentException("relative_path 不能包含首尾空白");
        if (path.isEmpty()) {
            if (allowEmpty) return "";
            throw new IllegalArgumentException("relative_path 不能为空");
        }
        if (path.length() > 512) throw new IllegalArgumentException("relative_path 过长");
        if (path.startsWith("/") || path.startsWith("\\") || path.contains(":")
                || path.contains("\\") || path.contains("%") || path.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("relative_path 必须是游戏目录内的普通相对路径");
        }
        String[] raw = path.split("/", -1);
        List<String> safe = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("relative_path 包含非法路径段");
            }
            if (segment.length() > 128) throw new IllegalArgumentException("relative_path 路径段过长");
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (Character.isISOControl(c) || (c >= '\u202A' && c <= '\u202E')
                        || (c >= '\u2066' && c <= '\u2069')) {
                    throw new IllegalArgumentException("relative_path 包含不可显示控制字符");
                }
            }
            safe.add(segment);
        }
        return String.join("/", safe);
    }

    static boolean isSensitive(String normalizedPath) {
        String value = normalizedPath == null ? "" : normalizedPath
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
        String[] segments = value.split("/");
        for (String segment : segments) {
            String stem = segment;
            int dot = segment.indexOf('.');
            if (dot > 0) stem = segment.substring(0, dot);
            String tokens = segment.replaceAll("[^a-z0-9]+", "_");
            java.util.Set<String> parts = new java.util.HashSet<>(java.util.Arrays.asList(tokens.split("_")));
            if (segment.equals(".env") || segment.startsWith(".env.") || segment.equals(".npmrc")
                    || segment.equals(".netrc") || segment.equals(".ssh") || segment.equals("id_rsa")
                    || segment.equals(".git-credentials") || tokens.contains("api_key")
                    || tokens.contains("client_secret") || stem.equals("session") || stem.equals("sessions")
                    || stem.equals("cookie") || stem.equals("cookies")
                    || parts.contains("token") || parts.contains("password") || parts.contains("passwd")
                    || parts.contains("credential") || parts.contains("credentials")
                    || parts.contains("account") || parts.contains("session") || parts.contains("cookie")
                    || parts.contains("cookies")
                    || stem.equals("credential") || stem.equals("credentials") || stem.equals("secret")
                    || stem.equals("secrets") || stem.equals("token") || stem.equals("password")
                    || stem.equals("passwd") || stem.equals("auth") || stem.equals("account")
                    || stem.equals("accounts") || stem.equals("profile") || stem.equals("save")
                    || stem.equals("saves") || stem.equals("savegame") || stem.equals("savegames")
                    || stem.equals("savedata") || stem.equals("save_data") || stem.equals("userdata")
                    || segment.endsWith(".rpgsave") || segment.endsWith(".rvdata") || segment.endsWith(".rvdata2")
                    || segment.endsWith(".sav")
                    || segment.endsWith(".pem") || segment.endsWith(".key") || segment.endsWith(".p12")
                    || segment.endsWith(".pfx") || segment.endsWith(".jks") || segment.endsWith(".keystore")) {
                return true;
            }
        }
        return false;
    }
}
