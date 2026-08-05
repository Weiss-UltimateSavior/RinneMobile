package com.core.agent.runtime;

import android.content.Context;
import android.util.Log;

import com.core.agent.net.McpHttpClient;
import com.core.agent.net.OpenAiCompatibleAgentClient;
import com.core.agent.store.AgentConfigStore;
import com.core.agent.store.AgentConversationRepository;
import com.core.agent.workspace.AgentPrivateWorkspace;
import com.core.agent.workspace.AgentScanRootGateway;
import com.core.agent.workspace.GameWorkspaceGateway;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 逐工具审批 + 执行 + 审计管线（重构计划 3.5 阶段 96 拆分自 LocalAgentRuntime）。
 *
 * 负责单轮内每个工具调用的授权门控（扫描目录/游戏目录/写入/MCP）、执行与
 * 审计落库；模型轮次循环与上下文管理保留在 {@link LocalAgentRuntime}。
 * 行为与原 LocalAgentRuntime.run() 内联实现逐字等价，仅将 continue 改为提前返回。
 */
final class AgentToolInvocation {
    private static final String TAG = "LocalAgentRuntime";

    private final Context appContext;
    private final AgentConversationRepository repository;

    // 页面会话级授权状态（随 Runtime 实例存活，跨运行轮次保留）
    private final Map<Long, String> sessionWorkspaceGrants = new HashMap<>();
    private boolean scanRootsGranted;

    AgentToolInvocation(Context appContext, AgentConversationRepository repository) {
        this.appContext = appContext;
        this.repository = repository;
    }

    /** 单次 run 的工具调用共享状态（由 run 循环持有，逐调用传入）。 */
    static final class ToolRoundState {
        final List<Long> pendingRows = new ArrayList<>();
        final Set<String> successfulMutationTools = new HashSet<>();
        String successfulMcpRegistration = "";
        boolean sideEffectsCommitted;
        final AtomicBoolean remoteMcpEffectUncertain = new AtomicBoolean(false);
    }

    /**
     * 处理一次工具调用：审批门控 → 执行 → 错误映射 → 审计落库。
     * 与模型轮次循环通过 [state]（可变共享状态）与 [messages] 交互。
     */
    void process(OpenAiCompatibleAgentClient.ToolCall call, LocalAgentRuntime.RunToken token,
                 LocalAgentRuntime.Callback callback, AgentConfigStore.Config config,
                 ToolRoundState state, List<OpenAiCompatibleAgentClient.ModelMessage> messages,
                 Consumer<McpHttpClient> mcpClientChanged) throws InterruptedException,
            AgentPrivateWorkspace.MutationFailure, AgentScanRootGateway.MutationFailure,
            GameWorkspaceGateway.WriteFailure, JSONException {
        LocalAgentRuntime.ensureActive(token);
        String toolName = call.name;
        LocalAgentRuntime.post(() -> callback.onToolStarted(toolName));
        String toolResult;
        boolean success = true;
        boolean mutationCommitted = false;
        boolean agentWorkspaceMutation = false;
        try {
            JSONObject arguments = new JSONObject(call.arguments.isEmpty() ? "{}" : call.arguments);
            agentWorkspaceMutation = AgentToolRegistry.isAgentWorkspaceMutation(toolName, arguments);
            if (AgentToolRegistry.isScanRootTool(toolName)
                    && !AgentToolRegistry.isScanRootMutation(toolName) && !scanRootsGranted) {
                boolean allowed = config.isFullPermission() || awaitApproval(callback, token,
                        "允许本次会话访问游戏扫描目录？",
                        "允许后，智能体可以查看你在游戏管理页添加的扫描目录标签与非敏感文件结构。目录信息会发送给已配置的网络模型服务。"
                                + "\n\n账号、密钥和存档路径仍会被本地规则阻止；关闭本页面即撤销授权。",
                        "仅本次允许");
                if (!allowed) {
                    toolResult = new JSONObject().put("error", "SCAN_ROOT_ACCESS_DENIED")
                            .put("message", "用户未授权本次会话访问游戏扫描目录").toString();
                    success = false;
                    state.pendingRows.add(repository.add("tool", "用户未授权扫描目录访问", toolName));
                    messages.add(new OpenAiCompatibleAgentClient.ModelMessage(
                            "tool", toolResult, toolName, call.id, null));
                    LocalAgentRuntime.post(() -> callback.onToolFinished(toolName, false));
                    return;
                }
                scanRootsGranted = true;
            }
            if (AgentToolRegistry.isWorkspaceTool(toolName)) {
                long gameId = AgentToolRegistry.workspaceGameId(appContext, toolName, arguments);
                String identity = GameWorkspaceGateway.rootIdentity(appContext, gameId);
                String granted = sessionWorkspaceGrants.get(gameId);
                if (!identity.equals(granted)) {
                    if (config.isFullPermission()) {
                        sessionWorkspaceGrants.put(gameId, identity);
                    } else {
                        String title = GameWorkspaceGateway.gameTitle(appContext, gameId);
                        boolean allowed = awaitApproval(callback, token,
                                "允许本次会话访问游戏目录？",
                                "游戏：" + title + "\n\n允许后，智能体可在本次页面会话中列出、搜索和读取该游戏目录的非敏感文本，读取结果会发送给你配置的网络模型服务。"
                                        + "\n\n常见 .env、密钥、账号与存档命名会被本地规则阻止。关闭本页面即撤销授权。\n\n是否允许？",
                                "仅本次允许");
                        if (!allowed) {
                            toolResult = new JSONObject().put("error", "WORKSPACE_ACCESS_DENIED")
                                    .put("message", "用户未授权本次会话访问该游戏目录").toString();
                            success = false;
                            state.pendingRows.add(repository.add("tool", "用户未授权游戏目录访问", toolName));
                            messages.add(new OpenAiCompatibleAgentClient.ModelMessage(
                                    "tool", toolResult, toolName, call.id, null));
                            LocalAgentRuntime.post(() -> callback.onToolFinished(toolName, false));
                            return;
                        }
                        if (!identity.equals(GameWorkspaceGateway.rootIdentity(appContext, gameId))) {
                            throw new IllegalStateException("确认期间游戏目录发生变化，请重试");
                        }
                        sessionWorkspaceGrants.put(gameId, identity);
                    }
                }
            }
            if (AgentToolRegistry.isScanRootMutation(toolName)) {
                AgentScanRootGateway.PendingOperation pending =
                        AgentToolRegistry.prepareScanRootOperation(appContext, arguments);
                boolean approved = config.isFullPermission() || awaitApproval(callback, token,
                        "确认整理游戏扫描目录", pending.preview, "确认整理");
                if (!approved) {
                    toolResult = new JSONObject().put("error", "USER_DENIED")
                            .put("message", "用户未批准本次扫描目录整理操作").toString();
                } else {
                    LocalAgentRuntime.ensureActive(token);
                    toolResult = AgentToolRegistry.executeApprovedScanRootOperation(
                            appContext, pending, token::isActive);
                    mutationCommitted = true;
                    scanRootsGranted = true;
                    state.successfulMutationTools.add(toolName);
                    state.sideEffectsCommitted = true;
                    token.markMutationCommitted();
                    try { repository.add("tool", "已确认并完成扫描目录整理；" + toolName, toolName); }
                    catch (RuntimeException logFailure) { Log.w(TAG, "audit-log-failed", logFailure); }
                }
            } else if (AgentToolRegistry.requiresApproval(toolName)) {
                GameWorkspaceGateway.PendingWrite pending = AgentToolRegistry.prepareWrite(
                        appContext, toolName, arguments);
                boolean approved = config.isFullPermission()
                        || awaitApproval(callback, token, toolName, pending);
                if (!approved) {
                    toolResult = new JSONObject().put("error", "USER_DENIED")
                            .put("message", "用户未批准本次文件修改").toString();
                } else {
                    LocalAgentRuntime.ensureActive(token);
                    toolResult = GameWorkspaceGateway.commitReplace(
                            appContext, pending, token::isActive, token::markMutationCommitted);
                    mutationCommitted = true;
                    state.successfulMutationTools.add(toolName);
                    state.sideEffectsCommitted = true;
                    try { repository.add("tool", "已确认并完成游戏文件修改；" + auditResult(toolResult), toolName); }
                    catch (RuntimeException logFailure) { Log.w(TAG, "audit-log-failed", logFailure); /* Snapshot metadata is the durable mutation journal. */ }
                }
            } else if (AgentToolRegistry.requiresMcpApproval(toolName)) {
                AgentToolRegistry.McpApproval pending = AgentToolRegistry.prepareMcpApproval(
                        appContext, toolName, arguments);
                boolean approved = config.isFullPermission()
                        || awaitApproval(callback, token, pending.title, pending.preview, pending.confirmText);
                if (!approved) {
                    toolResult = new JSONObject().put("error", "USER_DENIED")
                            .put("message", "用户未批准本次 MCP 操作").toString();
                } else {
                    LocalAgentRuntime.ensureActive(token);
                    toolResult = AgentToolRegistry.executeApprovedMcp(appContext, toolName, arguments,
                            token::isActive, new AgentToolRegistry.McpClientObserver() {
                                @Override public void onChanged(McpHttpClient mcpClient) {
                                    mcpClientChanged.accept(mcpClient);
                                }

                                @Override public void onToolRequestStarted() {
                                    state.remoteMcpEffectUncertain.set(true);
                                    try { repository.add("tool",
                                            "远程 MCP 工具请求已开始；若调用中断，服务器端执行状态可能未知",
                                            toolName); }
                                    catch (RuntimeException logFailure) { Log.w(TAG, "audit-log-failed", logFailure); }
                                }
                            });
                    if ("mcp_call_tool".equals(toolName)) state.remoteMcpEffectUncertain.set(false);
                    if ("add_mcp_server".equals(toolName)) {
                        JSONObject saved = new JSONObject(toolResult);
                        state.successfulMcpRegistration = "MCP「" + saved.optString("name") + "」已在本机添加成功。"
                                + "\n地址：" + saved.optString("endpoint")
                                + "\n服务器 ID：" + saved.optString("server_id");
                    }
                    state.sideEffectsCommitted = true;
                    try { repository.add("tool", "已确认 MCP 操作；" + toolName, toolName); }
                    catch (RuntimeException logFailure) { Log.w(TAG, "audit-log-failed", logFailure); }
                }
            } else {
                toolResult = AgentToolRegistry.execute(appContext, toolName, arguments, token::isActive,
                        mcpClient -> mcpClientChanged.accept(mcpClient));
            }
            success = !new JSONObject(toolResult).has("error");
            if (success && agentWorkspaceMutation) {
                mutationCommitted = true;
                state.successfulMutationTools.add(toolName);
                state.sideEffectsCommitted = true;
                token.markMutationCommitted();
                try {
                    String wsCommand = arguments.optString("command");
                    String wsAudit = "已完成智能体私有工作目录操作；" + wsCommand + " " + arguments.optString("relative_path");
                    if ("copy".equals(wsCommand) || "move".equals(wsCommand)) {
                        wsAudit += " -> " + arguments.optString("secondary_path");
                    }
                    repository.add("tool", wsAudit, toolName);
                }
                catch (RuntimeException logFailure) { Log.w(TAG, "audit-log-failed", logFailure); }
            }
        } catch (AgentPrivateWorkspace.MutationFailure error) {
            state.sideEffectsCommitted = true;
            token.markMutationCommitted();
            repository.add("tool", "智能体私有工作目录发生部分变更，操作未完整结束", toolName);
            throw error;
        } catch (AgentScanRootGateway.MutationFailure error) {
            state.sideEffectsCommitted = true;
            token.markMutationCommitted();
            repository.add("tool", "扫描目录已变化，但记录同步或结果校验失败", toolName);
            LocalAgentRuntime.post(() -> callback.onCriticalWarning("扫描目录整理异常",
                    "扫描目录可能已经发生变化，但游戏记录同步或结果校验失败。请在管理页重新扫描并人工检查目录。"));
            throw error;
        } catch (GameWorkspaceGateway.WriteFailure error) {
            state.sideEffectsCommitted = true;
            String audit = (error.restored ? "写入失败且已恢复" : "写入和恢复失败，文件可能损坏")
                    + "；文件=" + error.relativePath + "；快照=" + error.snapshotId;
            repository.add("tool", audit, toolName);
            String warning = audit + "\n\n请保留快照 ID，并在修改记录中恢复或人工检查文件。";
            LocalAgentRuntime.post(() -> callback.onCriticalWarning("游戏文件写入异常", warning));
            throw error;
        } catch (IllegalArgumentException error) {
            success = false;
            toolResult = new JSONObject().put("error", "INVALID_TOOL_ARGUMENTS")
                    .put("message", "工具参数无法解析或不符合要求").toString();
        } catch (Error error) {
            // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续推理
            throw error;
        } catch (Throwable error) {
            // 进程边界兜底：工具执行失败转为业务错误结果；Error 已在上方重抛
            success = false;
            toolResult = new JSONObject().put("error", "TOOL_EXECUTION_FAILED")
                    .put("message", safeToolError(toolName, error)).toString();
        }
        LocalAgentRuntime.ensureActive(token);
        String toolSummary = success
                ? (AgentToolRegistry.isScanRootMutation(toolName) ? "已确认并完成扫描目录整理"
                : agentWorkspaceMutation ? "已完成智能体工作目录操作"
                : AgentToolRegistry.requiresApproval(toolName) ? "已确认并完成游戏文件修改"
                : AgentToolRegistry.requiresMcpApproval(toolName) ? "已确认 MCP 操作" : "已完成本地只读查询")
                : "工具调用未完成";
        if (mutationCommitted) {
            // Mutation audit is persisted immediately at the commit boundary above.
        } else {
            state.pendingRows.add(repository.add("tool", toolSummary, toolName));
        }
        messages.add(new OpenAiCompatibleAgentClient.ModelMessage(
                "tool", toolResult, toolName, call.id, null));
        boolean deliveredSuccess = success;
        LocalAgentRuntime.post(() -> callback.onToolFinished(toolName, deliveredSuccess));
    }

    private boolean awaitApproval(LocalAgentRuntime.Callback callback, LocalAgentRuntime.RunToken token,
                                  String toolName, GameWorkspaceGateway.PendingWrite pending)
            throws InterruptedException {
        boolean restore = "restore_game_snapshot".equals(toolName);
        return awaitApproval(callback, token,
                (restore ? "确认恢复「" : "确认修改「") + pending.gameTitle + "」",
                pending.preview, restore ? "创建快照并恢复" : "创建快照并修改");
    }

    /** 通用审批等待（LocalAgentRuntime 上下文压缩审批与此共用）。 */
    static boolean awaitApproval(LocalAgentRuntime.Callback callback, LocalAgentRuntime.RunToken token,
                                 String title, String preview, String confirmText)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        AtomicBoolean resolved = new AtomicBoolean(false);
        LocalAgentRuntime.ApprovalResponder responder = value -> {
            if (resolved.compareAndSet(false, true)) {
                approved.set(value);
                latch.countDown();
            }
        };
        LocalAgentRuntime.post(() -> callback.onApprovalRequired(
                new LocalAgentRuntime.ApprovalRequest(title, preview, confirmText), responder));
        while (!latch.await(200, TimeUnit.MILLISECONDS)) LocalAgentRuntime.ensureActive(token);
        LocalAgentRuntime.ensureActive(token);
        return approved.get();
    }

    private static String safeToolError(String toolName, Throwable error) {
        if (error instanceof SecurityException) return "本地隐私规则拒绝访问该路径";
        if (error instanceof java.io.IOException) {
            if ("run_agent_workspace_command".equals(toolName)) {
                return "智能体工作目录操作失败或已超过本地容量限制";
            }
            if (AgentToolRegistry.isScanRootTool(toolName)) {
                return "游戏扫描目录不可访问、权限不足或目录已变化";
            }
            return "游戏文件访问失败、权限不足或文件已变化";
        }
        return "本地工具执行失败";
    }

    private static String auditResult(String result) {
        try {
            JSONObject value = new JSONObject(result);
            return "文件=" + value.optString("relative_path") + "；快照=" + value.optString("snapshot_id")
                    + "；新SHA-256=" + value.optString("after_sha256");
        } catch (JSONException ignored) { return "修改结果已本地记录"; }
    }
}
