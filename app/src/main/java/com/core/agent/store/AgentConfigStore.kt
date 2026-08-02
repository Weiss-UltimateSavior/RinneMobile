package com.core.agent.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local-only model configuration. Secrets never enter launcher sync or account preferences. */
object AgentConfigStore {
    private const val PREFS = "rinne_local_agent"
    private const val KEY_BASE_URL = "model_base_url"
    private const val KEY_MODEL = "model_name"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_SECRET = "api_key_encrypted"
    private const val KEY_TOOL_CALL_LIMIT = "tool_call_limit"
    private const val KEY_CONTEXT_BUDGET_KB = "context_budget_kb"
    private const val KEY_PLAN_ENABLED = "task_plan_enabled"
    private const val KEY_PERMISSION_MODE = "permission_mode"
    private const val KEYSTORE_ALIAS = "rinne_agent_api_key_v1"
    const val PERMISSION_RESTRICTED = "restricted"
    const val PERMISSION_FULL = "full"
    const val DEFAULT_CONTEXT_BUDGET_KB = 72

    class Config constructor(
        @JvmField val baseUrl: String?,
        @JvmField val model: String?,
        @JvmField val temperature: Float,
        @JvmField val hasApiKey: Boolean,
        toolCallLimit: Int,
        contextBudgetKb: Int,
        @JvmField val taskPlanEnabled: Boolean,
        permissionMode: String?
    ) {
        @JvmField val toolCallLimit: Int = clampToolCalls(toolCallLimit)
        @JvmField val contextBudgetKb: Int = clampContextBudgetKb(contextBudgetKb)
        @JvmField val permissionMode: String = normalizePermissionMode(permissionMode)

        fun isReady(): Boolean {
            val b = baseUrl
            val m = model
            return b != null && b.trim { it <= ' ' }.isNotEmpty() &&
                m != null && m.trim { it <= ' ' }.isNotEmpty() && hasApiKey
        }

        fun isFullPermission(): Boolean = PERMISSION_FULL == permissionMode

        fun contextBudgetChars(): Int = contextBudgetKb * 1024
    }

    @JvmStatic
    fun get(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            prefs.getString(KEY_BASE_URL, ""),
            prefs.getString(KEY_MODEL, ""),
            clampTemperature(prefs.getFloat(KEY_TEMPERATURE, 0.2f)),
            (prefs.getString(KEY_SECRET, "") ?: "").isNotEmpty(),
            prefs.getInt(KEY_TOOL_CALL_LIMIT, 5),
            prefs.getInt(KEY_CONTEXT_BUDGET_KB, DEFAULT_CONTEXT_BUDGET_KB),
            prefs.getBoolean(KEY_PLAN_ENABLED, true),
            prefs.getString(KEY_PERMISSION_MODE, PERMISSION_RESTRICTED)
        )
    }

    @JvmStatic
    @JvmName("getApiKey")
    @Throws(Exception::class)
    fun getApiKey(context: Context): String {
        val value = prefs(context).getString(KEY_SECRET, "") ?: ""
        if (value.isEmpty()) return ""
        try {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            if (packed.size < 13) throw IllegalStateException("密钥数据损坏")
            val iv = ByteArray(12)
            val encrypted = ByteArray(packed.size - iv.size)
            System.arraycopy(packed, 0, iv, 0, iv.size)
            System.arraycopy(packed, iv.size, encrypted, 0, encrypted.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            prefs(context).edit().remove(KEY_SECRET).apply()
            throw IllegalStateException("API Key 无法解密，请重新保存", error)
        }
    }

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun save(
        context: Context,
        baseUrl: String?,
        model: String?,
        temperature: Float,
        apiKey: String?,
        replaceApiKey: Boolean,
        toolCallLimit: Int,
        taskPlanEnabled: Boolean,
        permissionMode: String?
    ) {
        val safeUrl = validateBaseUrl(baseUrl)
        val safeModel = if (model == null) "" else model.trim { it <= ' ' }
        if (safeUrl.isEmpty()) throw IllegalArgumentException("请输入 API 地址")
        if (safeModel.isEmpty()) throw IllegalArgumentException("请输入模型名称")
        if (safeModel.length > 200) throw IllegalArgumentException("模型名称过长")
        val editor = prefs(context).edit()
            .putString(KEY_BASE_URL, safeUrl)
            .putString(KEY_MODEL, safeModel)
            .putFloat(KEY_TEMPERATURE, clampTemperature(temperature))
            .putInt(KEY_TOOL_CALL_LIMIT, validateToolCalls(toolCallLimit))
            .putBoolean(KEY_PLAN_ENABLED, taskPlanEnabled)
            .putString(KEY_PERMISSION_MODE, validatePermissionMode(permissionMode))
        if (replaceApiKey) {
            val safeKey = if (apiKey == null) "" else apiKey.trim { it <= ' ' }
            if (safeKey.isEmpty()) throw IllegalArgumentException("请输入 API Key")
            if (safeKey.length > 4096) throw IllegalArgumentException("API Key 过长")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(safeKey.toByteArray(StandardCharsets.UTF_8))
            val iv = cipher.iv
            val packed = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
            editor.putString(KEY_SECRET, Base64.encodeToString(packed, Base64.NO_WRAP))
        }
        editor.apply()
    }

    @JvmStatic
    @JvmName("validateToolCalls")
    fun validateToolCalls(value: Int): Int {
        if (value < 1 || value > 50) throw IllegalArgumentException("工具调用次数应为 1-50")
        return value
    }

    private fun clampToolCalls(value: Int): Int = maxOf(1, minOf(50, value))

    @JvmStatic
    @JvmName("validateContextBudgetKb")
    fun validateContextBudgetKb(value: Int): Int {
        if (value < 16 || value > 1024) throw IllegalArgumentException("上下文大小应为 16-1024K 字符")
        return value
    }

    private fun clampContextBudgetKb(value: Int): Int = maxOf(16, minOf(1024, value))

    @JvmStatic
    @JvmName("validatePermissionMode")
    fun validatePermissionMode(value: String?): String {
        val normalized = normalizePermissionMode(value)
        if (PERMISSION_RESTRICTED != normalized && PERMISSION_FULL != normalized) {
            throw IllegalArgumentException("权限模式无效")
        }
        return normalized
    }

    private fun normalizePermissionMode(value: String?): String {
        return if (value == null) PERMISSION_RESTRICTED
        else value.trim { it <= ' ' }.lowercase(Locale.ROOT)
    }

    @JvmStatic
    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(KEY_SECRET).apply()
    }

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun saveExecutionSettings(
        context: Context,
        toolCallLimit: Int,
        contextBudgetKb: Int,
        taskPlanEnabled: Boolean,
        fullPermission: Boolean
    ) {
        prefs(context).edit()
            .putInt(KEY_TOOL_CALL_LIMIT, validateToolCalls(toolCallLimit))
            .putInt(KEY_CONTEXT_BUDGET_KB, validateContextBudgetKb(contextBudgetKb))
            .putBoolean(KEY_PLAN_ENABLED, taskPlanEnabled)
            .putString(KEY_PERMISSION_MODE, if (fullPermission) PERMISSION_FULL else PERMISSION_RESTRICTED)
            .apply()
    }

    @JvmStatic
    @JvmName("chatCompletionsUrl")
    fun chatCompletionsUrl(baseUrl: String?): String {
        val value = normalizeBaseUrl(baseUrl)
        if (value.endsWith("/chat/completions")) return value
        if (value.endsWith("/v1")) return value + "/chat/completions"
        return value + "/v1/chat/completions"
    }

    @JvmStatic
    @JvmName("validateBaseUrl")
    fun validateBaseUrl(value: String?): String {
        val normalized = normalizeBaseUrl(value)
        if (normalized.isEmpty()) throw IllegalArgumentException("请输入 API 地址")
        if (normalized.length > 2048) throw IllegalArgumentException("API 地址过长")
        try {
            val uri = URI(normalized)
            val scheme = uri.scheme
            val host = uri.host
            if (scheme == null || host == null || host.trim { it <= ' ' }.isEmpty()) {
                throw IllegalArgumentException("API 地址格式不正确")
            }
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
                throw IllegalArgumentException("API 地址不能包含账号、查询参数或片段")
            }
            val local = "localhost".equals(host, ignoreCase = true) || "127.0.0.1" == host
            if (!"https".equals(scheme, ignoreCase = true) &&
                !(local && "http".equals(scheme, ignoreCase = true))
            ) {
                throw IllegalArgumentException("API 地址必须使用 HTTPS；仅 localhost/127.0.0.1 允许 HTTP")
            }
            return normalized
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("API 地址格式不正确", error)
        }
    }

    private fun normalizeBaseUrl(value: String?): String {
        var result = if (value == null) "" else value.trim { it <= ' ' }
        while (result.endsWith("/")) result = result.substring(0, result.length - 1)
        return result
    }

    private fun clampTemperature(value: Float): Float {
        if (value.isNaN()) return 0.2f
        return maxOf(0f, minOf(2f, value))
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Throws(Exception::class)
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val key = store.getKey(KEYSTORE_ALIAS, null)
        if (key is SecretKey) return key
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
