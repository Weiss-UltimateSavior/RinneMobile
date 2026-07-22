package com.yuki.yukihub.translation

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 智能翻译功能的本地配置存储。
 *
 * 复用 [com.apps.agent.AgentConfigStore] 的 AndroidKeyStore + AES/GCM 加密范式，
 * 但使用独立的 SharedPreferences 与 KeyStore alias，避免污染 Agent 配置。
 * 所有字段仅存储于本地，不进入云同步。
 */
object TranslationConfigStore {
    private const val PREFS = "rinne_translation"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model_name"
    private const val KEY_SECRET = "api_key_encrypted"
    private const val KEY_ENABLED = "enabled"
    private const val KEYSTORE_ALIAS = "rinne_translation_key_v1"

    /** 默认翻译 Prompt：识别游戏截图中的对话文本并翻译为中文。 */
    const val DEFAULT_PROMPT = "你是一名游戏对话翻译助手。请识别截图中的游戏对话文本并翻译为简体中文。" +
        "\n\n任务：\n" +
        "1. 仅识别对话框/气泡内的角色名与对话内容\n" +
        "2. 自动判断源语言（日文/英文/中文等），将原文翻译为简体中文" +
        "\n\n输出格式：\n" +
        "- 每行一条，格式：原文 -> 译文\n" +
        "- 按从上到下、从左到右的阅读顺序排列\n" +
        "- 若画面中无任何对话，仅回复：未检测到对话" +
        "\n\n严格要求：\n" +
        "- 只翻译对话框/气泡内的角色名和对话内容\n" +
        "- 禁止翻译、输出以下内容：菜单项、按钮、状态栏、系统提示、快捷键说明、设置项、存档/读档、自动/跳过/日志等 UI 控件文字\n" +
        "- 禁止编造或补全画面中未出现的对话内容\n" +
        "- 禁止添加任何解释、说明、注释或背景介绍\n" +
        "- 保持原文的语气和标点风格\n" +
        "- 角色名/专有名词可在译文中括注原文，如：樱（さくら）"

    data class Config(
        val baseUrl: String,
        val model: String,
        val hasApiKey: Boolean,
        val enabled: Boolean,
    ) {
        fun isReady(): Boolean =
            baseUrl.trim().isNotEmpty() && model.trim().isNotEmpty() && hasApiKey
    }

    @JvmStatic
    fun get(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            model = prefs.getString(KEY_MODEL, "") ?: "",
            hasApiKey = !(prefs.getString(KEY_SECRET, "") ?: "").isEmpty(),
            enabled = prefs.getBoolean(KEY_ENABLED, false),
        )
    }

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun save(context: Context, baseUrl: String, model: String, apiKey: String, replaceApiKey: Boolean) {
        val safeUrl = validateBaseUrl(baseUrl)
        val safeModel = model.trim()
        if (safeUrl.isEmpty()) throw IllegalArgumentException("请输入 API 地址")
        if (safeModel.isEmpty()) throw IllegalArgumentException("请输入模型名称")
        if (safeModel.length > 200) throw IllegalArgumentException("模型名称过长")
        val editor = prefs(context).edit()
            .putString(KEY_BASE_URL, safeUrl)
            .putString(KEY_MODEL, safeModel)
        if (replaceApiKey) {
            val safeKey = apiKey.trim()
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
    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(KEY_SECRET).apply()
    }

    /**
     * 解密 API Key。密钥损坏时清除并抛出异常。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun getApiKey(context: Context): String {
        val value = prefs(context).getString(KEY_SECRET, "") ?: ""
        if (value.isEmpty()) return ""
        return try {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            if (packed.size < 13) throw IllegalStateException("密钥数据损坏")
            val iv = ByteArray(12)
            val encrypted = ByteArray(packed.size - iv.size)
            System.arraycopy(packed, 0, iv, 0, iv.size)
            System.arraycopy(packed, iv.size, encrypted, 0, encrypted.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            prefs(context).edit().remove(KEY_SECRET).apply()
            throw IllegalStateException("API Key 无法解密，请重新保存", e)
        }
    }

    /**
     * 拼接 chat/completions 接口完整 URL。
     */
    @JvmStatic
    fun chatCompletionsUrl(baseUrl: String): String {
        val value = normalizeBaseUrl(baseUrl)
        if (value.endsWith("/chat/completions")) return value
        return if (value.endsWith("/v1")) "$value/chat/completions"
        else "$value/v1/chat/completions"
    }

    internal fun validateBaseUrl(value: String): String {
        val normalized = normalizeBaseUrl(value)
        if (normalized.isEmpty()) throw IllegalArgumentException("请输入 API 地址")
        if (normalized.length > 2048) throw IllegalArgumentException("API 地址过长")
        return try {
            val uri = URI(normalized)
            val scheme = uri.scheme
            val host = uri.host
            if (scheme == null || host == null || host.trim().isEmpty()) {
                throw IllegalArgumentException("API 地址格式不正确")
            }
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
                throw IllegalArgumentException("API 地址不能包含账号、查询参数或片段")
            }
            val local = "localhost".equals(host, ignoreCase = true) || "127.0.0.1" == host
            if (!"https".equals(scheme, ignoreCase = true) && !(local && "http".equals(scheme, ignoreCase = true))) {
                throw IllegalArgumentException("API 地址必须使用 HTTPS；仅 localhost/127.0.0.1 允许 HTTP")
            }
            normalized
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("API 地址格式不正确", e)
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        var result = value.trim()
        while (result.endsWith("/")) result = result.substring(0, result.length - 1)
        return result
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
