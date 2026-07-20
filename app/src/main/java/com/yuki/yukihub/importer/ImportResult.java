package com.yuki.yukihub.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入结果统计。
 *
 * 用于在 ImporterService.importGames / importSelected 执行完毕后，
 * 由调用方在主线程展示"成功 N，跳过 M，失败 K"摘要。
 */
public class ImportResult {

    public int success;
    public int skipped;
    public int failed;
    public int sessionsImported;

    public final List<String> failedNames = new ArrayList<>();
    public final List<String> skippedNames = new ArrayList<>();

    /**
     * 拼接一行摘要文本，用于 Toast / 对话框。
     */
    public String summary() {
        return "成功 " + success + "，跳过 " + skipped + "，失败 " + failed
                + (sessionsImported > 0 ? "，游玩记录 " + sessionsImported : "");
    }
}
