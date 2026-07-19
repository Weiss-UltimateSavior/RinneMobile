package com.yuki.yukihub.launcher

import com.yuki.yukihub.model.EngineType

/**
 * 启动策略注册表的不可变输入。
 *
 * - `engineType` 与 `packageName` 在构造时做 null 归一化（与原 Java 实现一致）：
 *   `engineType` → `UNKNOWN`，`packageName` → `""` 并 trim。
 * - 其余 5 个字段保留可空语义，用于 `if (request.X != null) intent.putExtra(...)` 的传递守卫
 *   （适配 JoiPlay 等外部插件的可选 extra）。
 * - `@JvmField val` 暴露公有 final 字段，保留 Java 直接字段读访问，且无 setter（不可变）。
 *
 * 注：未使用 `data class`。原 Java 实现的 `public final` 字段在构造时做归一化，
 * 而 Kotlin `val` 无法在 `init` 中重新赋值；若用 private 主构造器 + public 次构造器做归一化，
 * 两者 JVM 签名相同会触发 `platform declaration clash`。作为不可变请求 DTO，
 * equals/hashCode/toString/copy 的缺失不影响使用（原 Java 实现也未覆盖这些方法）。
 */
class LaunchRequest(
    engineType: EngineType?,
    packageName: String?,
    rootUri: String?,
    launchTarget: String?,
    winlatorLaunchMode: String?,
    gameHubLaunchMode: String?,
    gameHubLocalGameId: String?
) {
    @JvmField val engineType: EngineType = engineType ?: EngineType.UNKNOWN
    @JvmField val packageName: String = (packageName ?: "").trim()
    @JvmField val rootUri: String? = rootUri
    @JvmField val launchTarget: String? = launchTarget
    @JvmField val winlatorLaunchMode: String? = winlatorLaunchMode
    @JvmField val gameHubLaunchMode: String? = gameHubLaunchMode
    @JvmField val gameHubLocalGameId: String? = gameHubLocalGameId
}
