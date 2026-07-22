package com.yuki.yukihub.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.yuki.yukihub.net.HttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 多模态视觉翻译客户端。
 *
 * 构造 OpenAI 兼容的 vision 请求（content 数组含 text + image_url），
 * 发送 JPEG 字节流并解析返回的译文文本。不依赖现有 [com.apps.agent.OpenAiCompatibleAgentClient]，
 * 避免污染纯文本 Agent 流程。
 */
object VisionTranslationClient {

    private const val TAG = "VisionTranslation"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 翻译结果。
     *
     * @property success 是否成功
     * @property text 译文文本；失败时为错误信息
     */
    data class Result(val success: Boolean, val text: String) {
        companion object {
            @JvmStatic
            fun success(text: String): Result = Result(true, text)
            @JvmStatic
            fun failure(message: String): Result = Result(false, message)
        }
    }

    /**
     * 发送图片到多模态模型进行翻译。
     *
     * @param context 用于读取 API 配置
     * @param jpegBytes JPEG 格式的截图字节流
     * @param prompt 翻译指令
     * @return 翻译结果
     */
    @JvmStatic
    @JvmOverloads
    fun translate(context: Context, jpegBytes: ByteArray, prompt: String = TranslationConfigStore.DEFAULT_PROMPT): Result {
        val config = TranslationConfigStore.get(context)
        if (!config.isReady()) {
            return Result.failure("翻译功能未配置完整，请在设置页填写 API 地址、模型和 Key")
        }
        val apiKey = try {
            TranslationConfigStore.getApiKey(context)
        } catch (e: Exception) {
            return Result.failure("API Key 读取失败：${e.message ?: "未知错误"}")
        }
        if (apiKey.isEmpty()) {
            return Result.failure("API Key 为空，请重新配置")
        }

        val baseUrl = TranslationConfigStore.chatCompletionsUrl(config.baseUrl)
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val requestBody = buildRequestBody(config.model, prompt, base64Image)

        Log.i(TAG, "translate: model=${config.model} jpegBytes=${jpegBytes.size} requestLen=${requestBody.length}")

        val client = buildHttpClient()
        val request = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 上游 API 间歇性返回 500（Internal server error），重试 2 次提升成功率
        val maxRetries = 3
        var lastError: String = "未知错误"
        for (attempt in 1..maxRetries) {
            try {
                val requestStartedAt = System.nanoTime()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val elapsedMs = (System.nanoTime() - requestStartedAt) / 1_000_000L
                    Log.i(TAG, "response: attempt=$attempt code=${response.code} bodyLen=${body.length} elapsedMs=$elapsedMs")
                    if (!response.isSuccessful) {
                        lastError = formatHttpError(response.code, body)
                        if (response.code in 500..599 && attempt < maxRetries) {
                            Log.w(TAG, "server error, retrying (attempt=$attempt/$maxRetries)")
                            Thread.sleep(800L * attempt)
                            return@use
                        }
                        return Result.failure(lastError)
                    }
                    return parseTranslation(body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "translate request failed (attempt=$attempt)", e)
                lastError = "网络请求失败：${e.message ?: "未知错误"}"
                if (attempt < maxRetries) {
                    Thread.sleep(800L * attempt)
                }
            }
        }
        return Result.failure(lastError)
    }

    /**
     * 将 HTTP 错误响应转换为用户可读的提示。
     *
     * 常见 500 错误原因：模型不支持 vision（多模态图片输入）。
     * 例如 mimo-v2.5-free 等纯文本模型收到 image_url 会触发 Internal server error。
     */
    private fun formatHttpError(code: Int, body: String): String {
        val base = "HTTP $code"
        val detail = extractErrorMessage(body) ?: ""
        return when (code) {
            500, 502, 503 -> {
                if (detail.isNotEmpty()) {
                    "服务器错误($code)：$detail\n\n请检查模型是否支持 vision（如 gpt-4o-mini、gemini-flash），纯文本模型无法处理图片。"
                } else {
                    "服务器错误($code)，请检查模型是否支持图片输入"
                }
            }
            401, 403 -> "认证失败($code)：API Key 无效或无权限 $detail"
            429 -> "请求过于频繁($code)，请稍后再试 $detail"
            400 -> "请求格式错误($code)：$detail\n可能是模型不支持图片输入"
            else -> if (detail.isNotEmpty()) "$base：$detail" else base
        }
    }

    /**
     * 从 JSON 错误响应中提取 message 字段。
     * 兼容 OpenAI 格式 {"error":{"message":"..."}} 和裸字符串。
     */
    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            if (error != null) {
                error.optString("message", "").takeIf { it.isNotEmpty() }
            } else {
                json.optString("message", "").takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            truncate(body, 200)
        }
    }

    private fun buildRequestBody(model: String, prompt: String, base64Image: String): String {
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
            })
        }
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 1500)
            put("temperature", 0.2)
        }.toString()
    }

    private fun parseTranslation(body: String): Result {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return Result.failure("响应中无 choices 字段")
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
            if (message == null) {
                return Result.failure("响应中无 message 字段")
            }
            val content = message.optString("content", "")
            if (content.isEmpty()) return Result.failure("译文内容为空")
            Result.success(content.trim())
        } catch (e: Exception) {
            Result.failure("解析响应失败：${e.message ?: "未知错误"}")
        }
    }

    private fun buildHttpClient(): OkHttpClient {
        return HttpClient.defaultBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * 测试模型是否具备图像识别能力。
     *
     * 发送一张 2x2 的纯色 JPEG 图片，询问模型"图片中有几种颜色"。
     * 成功条件：HTTP 200 且能解析出 choices[0].message.content 非空。
     * 失败条件：HTTP 非 200、网络异常、JSON 解析失败。
     *
     * @param context 用于读取 API 配置
     * @param baseUrl 覆盖配置的 API 地址（为空则使用已保存配置）
     * @param model 覆盖配置的模型名称（为空则使用已保存配置）
     * @return 测试结果
     */
    @JvmStatic
    @JvmOverloads
    fun testVision(
        context: Context,
        baseUrl: String = "",
        model: String = "",
    ): Result {
        val config = TranslationConfigStore.get(context)
        val effectiveUrl = baseUrl.takeIf { it.trim().isNotEmpty() } ?: config.baseUrl
        val effectiveModel = model.takeIf { it.trim().isNotEmpty() } ?: config.model
        if (effectiveUrl.trim().isEmpty() || effectiveModel.trim().isEmpty()) {
            return Result.failure("请先填写 API 地址和模型名称")
        }
        val apiKey = try {
            TranslationConfigStore.getApiKey(context)
        } catch (e: Exception) {
            return Result.failure("API Key 读取失败：${e.message ?: "未知错误"}")
        }
        if (apiKey.isEmpty()) {
            return Result.failure("API Key 为空，请先保存配置")
        }

        val fullUrl = TranslationConfigStore.chatCompletionsUrl(effectiveUrl)
        val testImage = createTestImage()
        val base64Image = Base64.encodeToString(testImage, Base64.NO_WRAP)
        val requestBody = buildRequestBody(
            effectiveModel,
            "请用一句话描述这张图片中的主要颜色。",
            base64Image
        )

        Log.i(TAG, "testVision: model=$effectiveModel testImageBytes=${testImage.size}")

        val client = buildHttpClient()
        val request = Request.Builder()
            .url(fullUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            val requestStartedAt = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val elapsedMs = (System.nanoTime() - requestStartedAt) / 1_000_000L
                Log.i(TAG, "testVision response: code=${response.code} bodyLen=${body.length} elapsedMs=$elapsedMs")
                if (!response.isSuccessful) {
                    return Result.failure(formatHttpError(response.code, body))
                }
                val parseResult = parseTranslation(body)
                if (parseResult.success) {
                    Result.success(parseResult.text)
                } else {
                    Result.failure(parseResult.text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "testVision request failed", e)
            Result.failure("网络请求失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 生成一张 2x2 的纯绿色 JPEG 图片用于测试模型图像识别能力。
     * 体积极小（<1KB），避免浪费 Token。
     */
    private fun createTestImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.substring(0, max) + "..."
    }
}
