package com.core.launcher

/**
 * 引擎 scoped save 目录偏好键单源（com_apps_refactor_plan.md §9.12）。
 * 各模块的 scoped_save_dir / tyrano_external_network 读写统一收敛于此，避免字面量漂移。
 */
object EngineSaveKeys {
    const val KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir"
    const val KEY_ARTEMIS_SCOPED_SAVE_DIR = "artemis_scoped_save_dir"
    const val KEY_TYRANO_SCOPED_SAVE_DIR = "tyrano_scoped_save_dir"
    const val KEY_TYRANO_EXTERNAL_NETWORK = "tyrano_external_network"
}
