package com.yuki.yukihub.launcherbridge

import android.content.Context
import android.content.SharedPreferences
import com.yuki.yukihub.util.RxMainScheduler
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Shared plumbing for launcher bridges; keeps their Java-facing APIs unchanged. */
internal const val YUKIHUB_PREFS_NAME = "yukihub_prefs"

internal fun Context.yukiPrefs(): SharedPreferences =
    applicationContext.getSharedPreferences(YUKIHUB_PREFS_NAME, Context.MODE_PRIVATE)

internal fun postToMain(action: Runnable) {
    RxMainScheduler.post(action)
}

/**
 * Small, bounded HttpURLConnection client for the launcher-only API bridges.
 * Authentication-specific request-size and timeout policies remain in LauncherAuthBridge.
 */
internal object LauncherBridgeHttp {
    @Throws(Exception::class)
    fun request(
        url: String,
        method: String,
        body: String? = null,
        bearerToken: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        maxResponseBytes: Long,
        accept: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", accept)
            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val text = readLimited(
                if (code in 200..299) connection.inputStream else connection.errorStream,
                maxResponseBytes
            )
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${errorDetail(text)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    @Throws(Exception::class)
    fun readLimited(input: InputStream?, maxBytes: Long): String {
        if (input == null) return ""
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                total += count
                if (total > maxBytes) throw java.io.IOException("response too large")
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun errorDetail(text: String): String = try {
        when (val detail = JSONObject(text).opt("detail")) {
            is JSONObject -> detail.optString("message", text)
            null -> text
            else -> detail.toString()
        }
    } catch (_: Throwable) {
        text
    }
}
