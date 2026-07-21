package com.yuki.yukihub.importer

/**
 * 导入结果统计。
 *
 * 用于在 ImporterService.importGames / importSelected 执行完毕后，
 * 由调用方在主线程展示"成功 N，跳过 M，失败 K"摘要。
 *
 * - `@JvmField var` 保留 Java 调用方原有的直接字段读写（`result.success++`）。
 * - `@JvmField val` 列表保留 `result.failedNames.add(...)` 语法。
 */
class ImportResult {
    @JvmField var success: Int = 0
    @JvmField var skipped: Int = 0
    @JvmField var failed: Int = 0
    @JvmField var sessionsImported: Int = 0

    @JvmField val failedNames = mutableListOf<String>()
    @JvmField val skippedNames = mutableListOf<String>()

    /** 拼接一行摘要文本，用于 Toast / 对话框。 */
    fun summary(): String {
        return "成功 $success，跳过 $skipped，失败 $failed" +
                if (sessionsImported > 0) "，游玩记录 $sessionsImported" else ""
    }
}
