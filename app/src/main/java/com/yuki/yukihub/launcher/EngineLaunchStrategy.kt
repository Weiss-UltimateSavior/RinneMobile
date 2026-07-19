package com.yuki.yukihub.launcher

import android.content.Context
import com.yuki.yukihub.model.EngineType

/**
 * Launch contract for a concrete emulator/engine integration.
 *
 * Strategies must return `false` when their recognised integration cannot be started.
 * The caller deliberately does not fall through to a generic package launch in that
 * case: this preserves the previous behaviour for internal engines and prevents a
 * failed game launch from opening an emulator's home screen.
 *
 * 保留 3 个 `fun` 方法（而非 Kotlin 属性），与原 Java 接口签名完全一致：
 * 现有 Java 实现类（`ExternalGodotPluginStrategy` 等）`@Override` 不变。
 */
interface EngineLaunchStrategy {
    /** The primary engine this strategy implements, for discovery and extension. */
    fun getEngineType(): EngineType

    /** True only when this strategy owns the request's current package/protocol. */
    fun supports(request: LaunchRequest): Boolean

    /** Starts the request while preserving the engine's existing Intent contract. */
    fun launch(context: Context, request: LaunchRequest): Boolean
}
