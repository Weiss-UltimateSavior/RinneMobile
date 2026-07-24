package com.apps.agent

import java.util.Locale

/** Prevents the network model from reporting file mutations that the local executor did not verify. */
object AgentOutcomeGuard {
    @JvmStatic
    fun enforce(text: String?, successfulMutationTools: Set<String>?): String {
        val value = text?.trim().orEmpty()
        val successes = successfulMutationTools.orEmpty()
        val workspaceClaim = claimsPrivateWorkspaceSuccess(value)
        val scanRootClaim = claimsScanRootSuccess(value)
        val unverifiedRestore = claimsRestoreSuccess(value) && "restore_game_snapshot" !in successes
        val unverifiedReplace = claimsReplaceSuccess(value) && !workspaceClaim && !scanRootClaim &&
            "replace_game_text" !in successes
        val unverifiedWorkspace = workspaceClaim && "run_agent_workspace_command" !in successes
        val unverifiedScanRoot = scanRootClaim && "organize_scan_root" !in successes
        if (unverifiedWorkspace) {
            return "本轮没有获得 run_agent_workspace_command 的本地成功结果，因此不能确认智能体工作目录已经变化。" +
                "请以工具返回的 success、路径和校验值为准。"
        }
        if (unverifiedScanRoot) {
            return "本轮没有获得 organize_scan_root 的本地成功结果，因此不能确认游戏扫描目录已经整理完成。" +
                "请以本机确认后的工具结果为准。"
        }
        if (!unverifiedRestore && !unverifiedReplace) return value
        if (unverifiedRestore && unverifiedReplace) {
            return "本轮没有获得本地文件修改或快照恢复工具的成功结果，因此不能确认文件已经变化。" +
                "请明确要求调用对应工具，并以本机确认后的工具结果和文件校验为准。"
        }
        if (unverifiedRestore) {
            return "本轮没有获得 restore_game_snapshot 的本地成功结果，因此不能确认文件已经恢复。" +
                "请明确要求调用恢复工具，并以本机确认后的工具结果和文件校验为准。"
        }
        return "本轮没有获得 replace_game_text 的本地成功结果，因此不能确认文件已经修改。" +
            "请明确要求调用修改工具，并以本机确认后的工具结果和文件校验为准。"
    }

    @JvmStatic
    fun enforceMcpRegistration(text: String?, localConfirmation: String?): String {
        val value = text?.trim().orEmpty()
        val confirmation = localConfirmation?.trim().orEmpty()
        if (confirmation.isEmpty()) return value
        if (claimsMcpAddFailure(value)) {
            return confirmation + "\n\n模型后续表述与本机保存结果不一致，已以本地实际执行结果为准。" +
                "如需验证连通性，可以让我列出该 MCP 的工具。"
        }
        if (claimsMcpAddSuccess(value)) return value
        return if (value.isEmpty()) confirmation else "$confirmation\n\n$value"
    }

    @JvmStatic
    fun enforceMcpRegistry(text: String?, savedSummary: String?): String {
        val value = text?.trim().orEmpty()
        val summary = savedSummary?.trim().orEmpty()
        if (summary.isEmpty() || !claimsMissingMcpConfirmation(value)) return value
        return summary + "\n\n模型关于“未弹窗确认或未保存”的表述与本地注册表冲突，已忽略。" +
            "本轮尚未可靠获取功能清单，可重试读取该 MCP 的工具。"
    }

    private fun claimsMissingMcpConfirmation(value: String): Boolean {
        if (!value.lowercase(Locale.ROOT).contains("mcp")) return false
        return listOf("没有弹窗", "未弹窗", "没有确认", "未确认", "没有保存", "未保存", "没有添加", "未添加")
            .any(value::contains)
    }

    private fun claimsMcpAddFailure(value: String): Boolean =
        listOf("没有添加成功", "未添加成功", "添加失败", "未能添加", "没有成功添加", "无法确认已添加").any(value::contains)

    private fun claimsMcpAddSuccess(value: String): Boolean = value.contains("MCP") &&
        listOf("添加成功", "已成功添加", "已经添加", "已存在").any(value::contains)

    private fun claimsRestoreSuccess(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        if (listOf("恢复成功", "成功恢复", "已恢复").any { isNegated(value, it) }) return false
        return listOf("恢复成功", "成功恢复", "已恢复到", "恢复已完成").any(value::contains) ||
            lower.contains("restore_game_snapshot") && listOf("调用成功", "success=true", "success: true", "success` | ✅").any(lower::contains)
    }

    private fun claimsReplaceSuccess(value: String): Boolean {
        if (listOf("修改成功", "替换成功", "写入成功").any { isNegated(value, it) }) return false
        val lower = value.lowercase(Locale.ROOT)
        return listOf("修改成功", "替换成功", "写入成功", "替换已成功执行", "已成功写入").any(value::contains) ||
            lower.contains("replace_game_text") && listOf("调用成功", "success=true", "success: true", "success` | ✅").any(lower::contains)
    }

    private fun claimsPrivateWorkspaceSuccess(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        val scope = value.contains("工作目录") || value.contains("私有工作区") ||
            lower.contains("rinne_private") || lower.contains("run_agent_workspace_command")
        if (!scope) return false
        val claims = listOf("已写入", "已创建", "已删除", "已移动", "已复制", "写入成功", "创建成功", "删除成功", "移动成功", "复制成功")
        if (claims.any { isNegated(value, it) }) return false
        return claims.any(value::contains)
    }

    private fun claimsScanRootSuccess(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        if (!value.contains("扫描目录") && !lower.contains("organize_scan_root")) return false
        val claims = listOf("整理完成", "整理成功", "移动成功", "重命名成功", "创建目录成功", "整理已完成", "已移动", "已重命名", "已创建")
        if (claims.any { isNegated(value, it) }) return false
        return claims.any(value::contains)
    }

    private fun isNegated(value: String, claim: String): Boolean {
        val index = value.indexOf(claim)
        if (index < 0) return false
        val prefix = value.substring(maxOf(0, index - 8), index)
        return listOf("未", "没有", "无法", "不能", "并非").any(prefix::contains)
    }
}
