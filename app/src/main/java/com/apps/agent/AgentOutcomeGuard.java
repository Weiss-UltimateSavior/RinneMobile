package com.apps.agent;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/** Prevents the network model from reporting file mutations that the local executor did not verify. */
final class AgentOutcomeGuard {
    private AgentOutcomeGuard() { }

    static String enforce(String text, Set<String> successfulMutationTools) {
        String value = text == null ? "" : text.trim();
        Set<String> successes = successfulMutationTools == null
                ? Collections.emptySet() : successfulMutationTools;
        boolean unverifiedRestore = claimsRestoreSuccess(value)
                && !successes.contains("restore_game_snapshot");
        boolean unverifiedReplace = claimsReplaceSuccess(value)
                && !successes.contains("replace_game_text");
        if (!unverifiedRestore && !unverifiedReplace) return value;
        if (unverifiedRestore && unverifiedReplace) {
            return "本轮没有获得本地文件修改或快照恢复工具的成功结果，因此不能确认文件已经变化。"
                    + "请明确要求调用对应工具，并以本机确认后的工具结果和文件校验为准。";
        }
        if (unverifiedRestore) {
            return "本轮没有获得 restore_game_snapshot 的本地成功结果，因此不能确认文件已经恢复。"
                    + "请明确要求调用恢复工具，并以本机确认后的工具结果和文件校验为准。";
        }
        return "本轮没有获得 replace_game_text 的本地成功结果，因此不能确认文件已经修改。"
                + "请明确要求调用修改工具，并以本机确认后的工具结果和文件校验为准。";
    }

    static String enforceMcpRegistration(String text, String localConfirmation) {
        String value = text == null ? "" : text.trim();
        String confirmation = localConfirmation == null ? "" : localConfirmation.trim();
        if (confirmation.isEmpty()) return value;
        if (claimsMcpAddFailure(value)) {
            return confirmation + "\n\n模型后续表述与本机保存结果不一致，已以本地实际执行结果为准。"
                    + "如需验证连通性，可以让我列出该 MCP 的工具。";
        }
        if (claimsMcpAddSuccess(value)) return value;
        return value.isEmpty() ? confirmation : confirmation + "\n\n" + value;
    }

    static String enforceMcpRegistry(String text, String savedSummary) {
        String value = text == null ? "" : text.trim();
        String summary = savedSummary == null ? "" : savedSummary.trim();
        if (summary.isEmpty() || !claimsMissingMcpConfirmation(value)) return value;
        return summary + "\n\n模型关于“未弹窗确认或未保存”的表述与本地注册表冲突，已忽略。"
                + "本轮尚未可靠获取功能清单，可重试读取该 MCP 的工具。";
    }

    private static boolean claimsMissingMcpConfirmation(String value) {
        if (!value.toLowerCase(Locale.ROOT).contains("mcp")) return false;
        return value.contains("没有弹窗") || value.contains("未弹窗")
                || value.contains("没有确认") || value.contains("未确认")
                || value.contains("没有保存") || value.contains("未保存")
                || value.contains("没有添加") || value.contains("未添加");
    }

    private static boolean claimsMcpAddFailure(String value) {
        return value.contains("没有添加成功") || value.contains("未添加成功")
                || value.contains("添加失败") || value.contains("未能添加")
                || value.contains("没有成功添加") || value.contains("无法确认已添加");
    }

    private static boolean claimsMcpAddSuccess(String value) {
        return value.contains("MCP") && (value.contains("添加成功")
                || value.contains("已成功添加") || value.contains("已经添加")
                || value.contains("已存在"));
    }

    private static boolean claimsRestoreSuccess(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (isNegated(value, "恢复成功") || isNegated(value, "成功恢复") || isNegated(value, "已恢复")) {
            return false;
        }
        return value.contains("恢复成功") || value.contains("成功恢复")
                || value.contains("已恢复到") || value.contains("恢复已完成")
                || (lower.contains("restore_game_snapshot")
                && (lower.contains("调用成功") || lower.contains("success=true")
                || lower.contains("success: true") || lower.contains("success` | ✅")));
    }

    private static boolean claimsReplaceSuccess(String value) {
        if (isNegated(value, "修改成功") || isNegated(value, "替换成功") || isNegated(value, "写入成功")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("修改成功") || value.contains("替换成功") || value.contains("写入成功")
                || value.contains("替换已成功执行") || value.contains("已成功写入")
                || (lower.contains("replace_game_text")
                && (lower.contains("调用成功") || lower.contains("success=true")
                || lower.contains("success: true") || lower.contains("success` | ✅")));
    }

    private static boolean isNegated(String value, String claim) {
        int index = value.indexOf(claim);
        if (index < 0) return false;
        String prefix = value.substring(Math.max(0, index - 8), index);
        return prefix.contains("未") || prefix.contains("没有") || prefix.contains("无法")
                || prefix.contains("不能") || prefix.contains("并非");
    }
}
