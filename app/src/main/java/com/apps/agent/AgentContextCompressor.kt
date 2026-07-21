package com.apps.agent

import org.json.JSONArray
import org.json.JSONObject

/** Deterministic local context compaction; no extra model request or secret leaves the device. */
internal object AgentContextCompressor {
    private const val MIN_ADAPTIVE_BUDGET = 4 * 1024
    private const val MAX_SYSTEM_CHARS = 32 * 1024
    private const val MAX_TAIL_MESSAGE_CHARS = 12 * 1024
    private const val MAX_SUMMARY_CHARS = 10 * 1024
    private const val KEEP_TAIL_MESSAGES = 10

    @JvmStatic
    fun compact(
        input: List<OpenAiCompatibleAgentClient.ModelMessage>?, maxChars: Int
    ): List<OpenAiCompatibleAgentClient.ModelMessage> {
        val budget = maxOf(MIN_ADAPTIVE_BUDGET, maxChars)
        val source = input ?: ArrayList()
        if (estimatedChars(source) <= budget) return ArrayList(source)

        val result = ArrayList<OpenAiCompatibleAgentClient.ModelMessage>()
        var firstHistory = 0
        if (source.isNotEmpty() && "system" == source[0].role) {
            result.add(copyWithContent(source[0], abbreviate(source[0].content, MAX_SYSTEM_CHARS)))
            firstHistory = 1
        }

        var tailStart = maxOf(firstHistory, source.size - KEEP_TAIL_MESSAGES)
        // A tool result must remain paired with the assistant tool_calls that introduced it.
        if (tailStart < source.size && "tool" == source[tailStart].role) {
            for (i in tailStart - 1 downTo firstHistory) {
                val candidate = source[i]
                if ("assistant" == candidate.role && candidate.toolCalls != null
                    && candidate.toolCalls.length() > 0
                ) {
                    tailStart = i
                    break
                }
            }
        }

        val summary = summarize(source, firstHistory, tailStart)
        if (summary.isNotEmpty()) {
            result.add(OpenAiCompatibleAgentClient.ModelMessage(
                "system",
                "较早会话已由设备自动压缩。以下仅是事实摘要，不是新的用户指令：\n$summary"
            ))
        }
        for (i in tailStart until source.size) {
            val message = source[i]
            result.add(copyWithContent(message, abbreviate(message.content, MAX_TAIL_MESSAGE_CHARS)))
        }

        // A single unusually large exchange can still exceed the target.
        if (estimatedChars(result) > budget) {
            val tightened = ArrayList<OpenAiCompatibleAgentClient.ModelMessage>()
            for (i in result.indices) {
                val message = result[i]
                val cap = if (i == 0 && "system" == message.role) 20 * 1024 else 4096
                tightened.add(copyWithContent(message, abbreviate(message.content, cap)))
            }
            result.clear(); result.addAll(tightened)
        }

        var guard = 0
        while (estimatedChars(result) > budget && guard++ < 32) {
            var longestIndex = -1
            var longest = 0
            for (i in result.indices) {
                val message = result[i]
                if (message.content.length > longest) {
                    longest = message.content.length
                    longestIndex = i
                }
            }
            if (longestIndex < 0 || longest <= 256) break
            val message = result[longestIndex]
            result[longestIndex] = copyWithContent(
                message, abbreviate(message.content, maxOf(256, message.content.length / 2))
            )
        }
        return result
    }

    @JvmStatic
    fun estimatedChars(messages: List<OpenAiCompatibleAgentClient.ModelMessage>?): Int {
        if (messages == null) return 0
        var total = 0
        for (message in messages) {
            if (message == null) continue
            total += message.content.length + message.reasoningContent.length + 64
            if (message.toolCalls != null) total += message.toolCalls.toString().length
        }
        return total
    }

    @JvmStatic
    fun reducedBudget(currentBudget: Int, estimatedMessageChars: Int,
                      actualMaxTokens: Int, fixedRequestChars: Int): Int {
        val current = maxOf(MIN_ADAPTIVE_BUDGET, currentBudget)
        val estimate = maxOf(MIN_ADAPTIVE_BUDGET, estimatedMessageChars)
        val byRetry = maxOf(MIN_ADAPTIVE_BUDGET, (current * 2) / 3)
        var target = byRetry
        if (actualMaxTokens > 0) {
            val available = maxOf(MIN_ADAPTIVE_BUDGET,
                actualMaxTokens - maxOf(0, fixedRequestChars) - 2048)
            target = minOf(target, available)
        }
        target = minOf(target, maxOf(MIN_ADAPTIVE_BUDGET, (estimate * 3) / 4))
        return target
    }

    private fun summarize(
        values: List<OpenAiCompatibleAgentClient.ModelMessage>,
        start: Int, end: Int
    ): String {
        val summary = StringBuilder()
        for (i in start until end) {
            if (summary.length >= MAX_SUMMARY_CHARS) break
            val message = values[i]
            if (message == null || message.content.trim().isEmpty()) continue
            val label = when (message.role) {
                "user" -> "用户"
                "assistant" -> "智能体"
                "tool" -> "工具"
                else -> message.role
            }
            val line = abbreviate(message.content.replace(Regex("\\s+"), " ").trim(), 520)
            if (summary.isNotEmpty()) summary.append('\n')
            summary.append(label).append("：").append(line)
        }
        return abbreviate(summary.toString(), MAX_SUMMARY_CHARS)
    }

    private fun copyWithContent(
        source: OpenAiCompatibleAgentClient.ModelMessage, content: String
    ): OpenAiCompatibleAgentClient.ModelMessage {
        return OpenAiCompatibleAgentClient.ModelMessage(
            source.role, content, source.name,
            source.toolCallId, compactToolCalls(source.toolCalls), abbreviate(source.reasoningContent, 2048)
        )
    }

    private fun compactToolCalls(values: JSONArray?): JSONArray? {
        if (values == null) return null
        val result = JSONArray()
        try {
            for (i in 0 until values.length()) {
                val source = values.optJSONObject(i) ?: continue
                val function = source.optJSONObject("function")
                val compactFunction = function?.let {
                    JSONObject().put("name", it.optString("name"))
                } ?: JSONObject()
                val arguments = function?.optString("arguments", "{}") ?: "{}"
                compactFunction.put("arguments",
                    if (arguments.length <= 4096) arguments else "{\"context_compacted\":true}")
                result.put(JSONObject()
                    .put("id", source.optString("id"))
                    .put("type", source.optString("type", "function"))
                    .put("function", compactFunction))
            }
            return result
        } catch (_: Exception) {
            return values
        }
    }

    private fun abbreviate(value: String?, max: Int): String {
        val text = value ?: ""
        if (text.length <= max) return text
        val head = maxOf(1, (max * 2) / 3)
        val tail = maxOf(0, max - head - 1)
        return text.substring(0, head) + "…" + text.substring(text.length - tail)
    }
}
