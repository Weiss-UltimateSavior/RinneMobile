package com.core.prefs

/**
 * 扫描目录偏好键单一来源。
 *
 * 供 com.apps（ScanDirectoryController）、com.core（SyncManager / AgentScanRootGateway /
 * LauncherUserData）跨模块引用，避免偏好键在多个模块重复声明字面量。
 */
object ScanRootKeys {
    const val KEY_SCAN_ROOT_URIS = "scan_root_uris"
    const val KEY_SCAN_ROOT_ENABLED = "scan_root_enabled"
    const val KEY_LAST_SCAN_ROOT_URI = "last_scan_root_uri"
    const val KEY_STARTUP_SCAN_DEPTH = "startup_scan_depth"
}
