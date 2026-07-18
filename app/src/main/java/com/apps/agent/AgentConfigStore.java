package com.apps.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local-only model configuration. Secrets never enter launcher sync or account preferences. */
public final class AgentConfigStore {
    private static final String PREFS = "rinne_local_agent";
    private static final String KEY_BASE_URL = "model_base_url";
    private static final String KEY_MODEL = "model_name";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_SECRET = "api_key_encrypted";
    private static final String KEY_TOOL_CALL_LIMIT = "tool_call_limit";
    private static final String KEY_CONTEXT_BUDGET_KB = "context_budget_kb";
    private static final String KEY_PLAN_ENABLED = "task_plan_enabled";
    private static final String KEY_PERMISSION_MODE = "permission_mode";
    private static final String KEYSTORE_ALIAS = "rinne_agent_api_key_v1";
    static final String PERMISSION_RESTRICTED = "restricted";
    static final String PERMISSION_FULL = "full";
    static final int DEFAULT_CONTEXT_BUDGET_KB = 72;

    private AgentConfigStore() { }

    public static final class Config {
        public final String baseUrl;
        public final String model;
        public final float temperature;
        public final boolean hasApiKey;
        public final int toolCallLimit;
        public final int contextBudgetKb;
        public final boolean taskPlanEnabled;
        public final String permissionMode;

        Config(String baseUrl, String model, float temperature, boolean hasApiKey,
               int toolCallLimit, int contextBudgetKb, boolean taskPlanEnabled, String permissionMode) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.temperature = temperature;
            this.hasApiKey = hasApiKey;
            this.toolCallLimit = clampToolCalls(toolCallLimit);
            this.contextBudgetKb = clampContextBudgetKb(contextBudgetKb);
            this.taskPlanEnabled = taskPlanEnabled;
            this.permissionMode = normalizePermissionMode(permissionMode);
        }

        public boolean isReady() {
            return !baseUrl.trim().isEmpty() && !model.trim().isEmpty() && hasApiKey;
        }

        public boolean isFullPermission() { return PERMISSION_FULL.equals(permissionMode); }
        public int contextBudgetChars() { return contextBudgetKb * 1024; }
    }

    public static Config get(Context context) {
        SharedPreferences prefs = prefs(context);
        return new Config(
                prefs.getString(KEY_BASE_URL, ""),
                prefs.getString(KEY_MODEL, ""),
                clampTemperature(prefs.getFloat(KEY_TEMPERATURE, 0.2f)),
                !prefs.getString(KEY_SECRET, "").isEmpty(),
                prefs.getInt(KEY_TOOL_CALL_LIMIT, 5),
                prefs.getInt(KEY_CONTEXT_BUDGET_KB, DEFAULT_CONTEXT_BUDGET_KB),
                prefs.getBoolean(KEY_PLAN_ENABLED, true),
                prefs.getString(KEY_PERMISSION_MODE, PERMISSION_RESTRICTED)
        );
    }

    static String getApiKey(Context context) throws Exception {
        String value = prefs(context).getString(KEY_SECRET, "");
        if (value.isEmpty()) return "";
        try {
            byte[] packed = Base64.decode(value, Base64.NO_WRAP);
            if (packed.length < 13) throw new IllegalStateException("密钥数据损坏");
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[packed.length - iv.length];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            prefs(context).edit().remove(KEY_SECRET).apply();
            throw new IllegalStateException("API Key 无法解密，请重新保存", error);
        }
    }

    public static void save(Context context, String baseUrl, String model, float temperature,
                            String apiKey, boolean replaceApiKey, int toolCallLimit,
                            boolean taskPlanEnabled, String permissionMode) throws Exception {
        String safeUrl = validateBaseUrl(baseUrl);
        String safeModel = model == null ? "" : model.trim();
        if (safeUrl.isEmpty()) throw new IllegalArgumentException("请输入 API 地址");
        if (safeModel.isEmpty()) throw new IllegalArgumentException("请输入模型名称");
        if (safeModel.length() > 200) throw new IllegalArgumentException("模型名称过长");
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_BASE_URL, safeUrl)
                .putString(KEY_MODEL, safeModel)
                .putFloat(KEY_TEMPERATURE, clampTemperature(temperature))
                .putInt(KEY_TOOL_CALL_LIMIT, validateToolCalls(toolCallLimit))
                .putBoolean(KEY_PLAN_ENABLED, taskPlanEnabled)
                .putString(KEY_PERMISSION_MODE, validatePermissionMode(permissionMode));
        if (replaceApiKey) {
            String safeKey = apiKey == null ? "" : apiKey.trim();
            if (safeKey.isEmpty()) throw new IllegalArgumentException("请输入 API Key");
            if (safeKey.length() > 4096) throw new IllegalArgumentException("API Key 过长");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] encrypted = cipher.doFinal(safeKey.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            editor.putString(KEY_SECRET, Base64.encodeToString(packed, Base64.NO_WRAP));
        }
        editor.apply();
    }

    static int validateToolCalls(int value) {
        if (value < 1 || value > 50) throw new IllegalArgumentException("工具调用次数应为 1-50");
        return value;
    }

    private static int clampToolCalls(int value) { return Math.max(1, Math.min(50, value)); }

    static int validateContextBudgetKb(int value) {
        if (value < 16 || value > 1024) throw new IllegalArgumentException("上下文大小应为 16-1024K 字符");
        return value;
    }

    private static int clampContextBudgetKb(int value) { return Math.max(16, Math.min(1024, value)); }

    static String validatePermissionMode(String value) {
        String normalized = normalizePermissionMode(value);
        if (!PERMISSION_RESTRICTED.equals(normalized) && !PERMISSION_FULL.equals(normalized)) {
            throw new IllegalArgumentException("权限模式无效");
        }
        return normalized;
    }

    private static String normalizePermissionMode(String value) {
        return value == null ? PERMISSION_RESTRICTED : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void clearApiKey(Context context) {
        prefs(context).edit().remove(KEY_SECRET).apply();
    }

    public static void saveExecutionSettings(Context context, int toolCallLimit, int contextBudgetKb,
                                             boolean taskPlanEnabled, boolean fullPermission) {
        prefs(context).edit()
                .putInt(KEY_TOOL_CALL_LIMIT, validateToolCalls(toolCallLimit))
                .putInt(KEY_CONTEXT_BUDGET_KB, validateContextBudgetKb(contextBudgetKb))
                .putBoolean(KEY_PLAN_ENABLED, taskPlanEnabled)
                .putString(KEY_PERMISSION_MODE, fullPermission ? PERMISSION_FULL : PERMISSION_RESTRICTED)
                .apply();
    }

    static String chatCompletionsUrl(String baseUrl) {
        String value = normalizeBaseUrl(baseUrl);
        if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions";
        return value + "/v1/chat/completions";
    }

    static String validateBaseUrl(String value) {
        String normalized = normalizeBaseUrl(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException("请输入 API 地址");
        if (normalized.length() > 2048) throw new IllegalArgumentException("API 地址过长");
        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("API 地址格式不正确");
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("API 地址不能包含账号、查询参数或片段");
            }
            boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
            if (!"https".equalsIgnoreCase(scheme) && !(local && "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("API 地址必须使用 HTTPS；仅 localhost/127.0.0.1 允许 HTTP");
            }
            return normalized;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("API 地址格式不正确", error);
        }
    }

    private static String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static float clampTemperature(float value) {
        if (Float.isNaN(value)) return 0.2f;
        return Math.max(0f, Math.min(2f, value));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key key = store.getKey(KEYSTORE_ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
