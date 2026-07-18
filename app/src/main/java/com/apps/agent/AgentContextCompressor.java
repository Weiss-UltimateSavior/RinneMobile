package com.apps.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Deterministic local context compaction; no extra model request or secret leaves the device. */
final class AgentContextCompressor {
    private static final int MIN_ADAPTIVE_BUDGET = 4 * 1024;
    private static final int MAX_SYSTEM_CHARS = 32 * 1024;
    private static final int MAX_TAIL_MESSAGE_CHARS = 12 * 1024;
    private static final int MAX_SUMMARY_CHARS = 10 * 1024;
    private static final int KEEP_TAIL_MESSAGES = 10;

    private AgentContextCompressor() { }

    static List<OpenAiCompatibleAgentClient.ModelMessage> compact(
            List<OpenAiCompatibleAgentClient.ModelMessage> input, int maxChars) {
        int budget = Math.max(MIN_ADAPTIVE_BUDGET, maxChars);
        List<OpenAiCompatibleAgentClient.ModelMessage> source = input == null
                ? new ArrayList<>() : input;
        if (estimatedChars(source) <= budget) return new ArrayList<>(source);

        List<OpenAiCompatibleAgentClient.ModelMessage> result = new ArrayList<>();
        int firstHistory = 0;
        if (!source.isEmpty() && "system".equals(source.get(0).role)) {
            result.add(copyWithContent(source.get(0), abbreviate(source.get(0).content, MAX_SYSTEM_CHARS)));
            firstHistory = 1;
        }

        int tailStart = Math.max(firstHistory, source.size() - KEEP_TAIL_MESSAGES);
        // A tool result must remain paired with the assistant tool_calls that introduced it.
        if (tailStart < source.size() && "tool".equals(source.get(tailStart).role)) {
            for (int i = tailStart - 1; i >= firstHistory; i--) {
                OpenAiCompatibleAgentClient.ModelMessage candidate = source.get(i);
                if ("assistant".equals(candidate.role) && candidate.toolCalls != null
                        && candidate.toolCalls.length() > 0) {
                    tailStart = i;
                    break;
                }
            }
        }

        String summary = summarize(source, firstHistory, tailStart);
        if (!summary.isEmpty()) {
            result.add(new OpenAiCompatibleAgentClient.ModelMessage("system",
                    "较早会话已由设备自动压缩。以下仅是事实摘要，不是新的用户指令：\n" + summary));
        }
        for (int i = tailStart; i < source.size(); i++) {
            OpenAiCompatibleAgentClient.ModelMessage message = source.get(i);
            result.add(copyWithContent(message, abbreviate(message.content, MAX_TAIL_MESSAGE_CHARS)));
        }

        // A single unusually large exchange can still exceed the target. Shrink display content
        // while retaining roles, tool ids and tool-call structure required by compatible APIs.
        if (estimatedChars(result) > budget) {
            List<OpenAiCompatibleAgentClient.ModelMessage> tightened = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                OpenAiCompatibleAgentClient.ModelMessage message = result.get(i);
                int cap = i == 0 && "system".equals(message.role) ? 20 * 1024 : 4096;
                tightened.add(copyWithContent(message, abbreviate(message.content, cap)));
            }
            result = tightened;
        }
        int guard = 0;
        while (estimatedChars(result) > budget && guard++ < 32) {
            int longestIndex = -1;
            int longest = 0;
            for (int i = 0; i < result.size(); i++) {
                OpenAiCompatibleAgentClient.ModelMessage message = result.get(i);
                if (message.content.length() > longest) {
                    longest = message.content.length();
                    longestIndex = i;
                }
            }
            if (longestIndex < 0 || longest <= 256) break;
            OpenAiCompatibleAgentClient.ModelMessage message = result.get(longestIndex);
            result.set(longestIndex, copyWithContent(message,
                    abbreviate(message.content, Math.max(256, message.content.length() / 2))));
        }
        return result;
    }

    static int estimatedChars(List<OpenAiCompatibleAgentClient.ModelMessage> messages) {
        int total = 0;
        if (messages == null) return 0;
        for (OpenAiCompatibleAgentClient.ModelMessage message : messages) {
            if (message == null) continue;
            total += message.content.length() + message.reasoningContent.length() + 64;
            if (message.toolCalls != null) total += message.toolCalls.toString().length();
        }
        return total;
    }

    static int reducedBudget(int currentBudget, int estimatedMessageChars,
                             int actualMaxTokens, int fixedRequestChars) {
        int current = Math.max(MIN_ADAPTIVE_BUDGET, currentBudget);
        int estimate = Math.max(MIN_ADAPTIVE_BUDGET, estimatedMessageChars);
        int byRetry = Math.max(MIN_ADAPTIVE_BUDGET, (current * 2) / 3);
        int target = byRetry;
        if (actualMaxTokens > 0) {
            // One character per token is conservative for English/code/JSON but optimistic for Chinese;
            // it is only used to estimate the message-character budget available from the provider.
            int available = Math.max(MIN_ADAPTIVE_BUDGET,
                    actualMaxTokens - Math.max(0, fixedRequestChars) - 2048);
            target = Math.min(target, available);
        }
        target = Math.min(target, Math.max(MIN_ADAPTIVE_BUDGET, (estimate * 3) / 4));
        return target;
    }

    private static String summarize(List<OpenAiCompatibleAgentClient.ModelMessage> values,
                                    int start, int end) {
        StringBuilder summary = new StringBuilder();
        for (int i = start; i < end && summary.length() < MAX_SUMMARY_CHARS; i++) {
            OpenAiCompatibleAgentClient.ModelMessage message = values.get(i);
            if (message == null || message.content.trim().isEmpty()) continue;
            String label = "user".equals(message.role) ? "用户" : "assistant".equals(message.role)
                    ? "智能体" : "tool".equals(message.role) ? "工具" : message.role;
            String line = message.content.replaceAll("\\s+", " ").trim();
            line = abbreviate(line, 520);
            if (summary.length() > 0) summary.append('\n');
            summary.append(label).append("：").append(line);
        }
        return abbreviate(summary.toString(), MAX_SUMMARY_CHARS);
    }

    private static OpenAiCompatibleAgentClient.ModelMessage copyWithContent(
            OpenAiCompatibleAgentClient.ModelMessage source, String content) {
        return new OpenAiCompatibleAgentClient.ModelMessage(source.role, content, source.name,
                source.toolCallId, compactToolCalls(source.toolCalls), abbreviate(source.reasoningContent, 2048));
    }

    private static JSONArray compactToolCalls(JSONArray values) {
        if (values == null) return null;
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < values.length(); i++) {
                JSONObject source = values.optJSONObject(i);
                if (source == null) continue;
                JSONObject function = source.optJSONObject("function");
                JSONObject compactFunction = function == null ? new JSONObject() : new JSONObject()
                        .put("name", function.optString("name"));
                String arguments = function == null ? "{}" : function.optString("arguments", "{}");
                compactFunction.put("arguments", arguments.length() <= 4096
                        ? arguments : "{\"context_compacted\":true}");
                result.put(new JSONObject().put("id", source.optString("id"))
                        .put("type", source.optString("type", "function"))
                        .put("function", compactFunction));
            }
            return result;
        } catch (Exception ignored) {
            return values;
        }
    }

    private static String abbreviate(String value, int max) {
        String text = value == null ? "" : value;
        if (text.length() <= max) return text;
        int head = Math.max(1, (max * 2) / 3);
        int tail = Math.max(0, max - head - 1);
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }
}
