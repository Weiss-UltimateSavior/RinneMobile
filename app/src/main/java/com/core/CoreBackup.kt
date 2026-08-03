package com.core

/**
 * 跨模块共享备份文件命名常量（单源）。
 * 主源在 com.core（com.core 不得反向依赖 com.apps，故在此统一定义）；
 * 上层 LauncherSyncCenterActivity / LocalBackupController 均引用本常量，禁止散布字面量。
 */
object CoreBackup {
    const val FILE_PREFIX = "yukihub_backup_"
}
