package com.yuki.yukihub.importer

/**
 * 三方平台导入时的单条游戏数据（中间格式）。
 *
 * 各 Importer 把原始格式（Playnite JSON / PotatoVN ZIP / Vnite 目录 / LunaBox ZIP）
 * 解析成 ImportGameData，再由 ImporterService 统一写库。
 *
 * 字段命名与 [com.yuki.yukihub.model.Game] 对齐，便于在 Service 层直接映射。
 *
 * - `@JvmField var` + `@JvmOverloads constructor` 保留 Java 调用方原有的
 *   `new ImportGameData()` 无参构造 + 逐字段赋值语法。
 */
data class ImportGameData @JvmOverloads constructor(
    /** 游戏标题（显示名） */
    @JvmField var name: String = "",
    /** 原始标题（如有） */
    @JvmField var originalName: String? = null,
    @JvmField var developer: String? = null,
    @JvmField var description: String? = null,
    /** 远程封面 URL（http/https） */
    @JvmField var coverUrl: String? = null,
    /** 本地封面路径（ZIP 内解压的文件） */
    @JvmField var coverLocalPath: String? = null,
    /** YYYY-MM-DD */
    @JvmField var releaseDate: String? = null,
    @JvmField var rating: Double = 0.0,
    /** 游戏路径（Windows 路径，Android 上可能无效） */
    @JvmField var path: String? = null,
    @JvmField var savePath: String? = null,
    /** vndb / bangumi / ymgal / steam / local */
    @JvmField var sourceType: String? = null,
    @JvmField var sourceId: String? = null,
    @JvmField var tags: List<String>? = null,
    @JvmField var createdAt: Long = 0L,
    /** 总游戏时长（秒） */
    @JvmField var totalPlayTime: Long = 0L,
    /** 游玩状态：unplayed / playing / completed（来自 LunaBox status 映射） */
    @JvmField var playStatus: String? = null,

    // ===== 预览阶段字段 =====
    /** 是否已有同标题游戏（预览标记） */
    @JvmField var exists: Boolean = false,
    /** 用户是否勾选导入此条 */
    @JvmField var selected: Boolean = false,
    /** 如果已存在，标记原因 */
    @JvmField var conflictReason: String? = null,

    // ===== 各平台游玩记录原始结构 =====
    /** PotatoVN PlayedTime: date -> minutes，由 ImporterService 转成 play_sessions */
    @JvmField var playedTimeMap: Map<String, Int>? = null,
    /** Vnite Timers: 每条有 start / end 时间字符串 */
    @JvmField var vniteTimers: List<VniteTimer>? = null,
    /** LunaBox Sessions: 每条有 start / end 时间字符串 + duration(秒) */
    @JvmField var lunaBoxSessions: List<LunaBoxSession>? = null
) {
    /** Vnite 计时器条目。 */
    data class VniteTimer @JvmOverloads constructor(
        @JvmField var start: String? = null,
        @JvmField var end: String? = null
    )

    /** LunaBox 游玩会话条目。 */
    data class LunaBoxSession @JvmOverloads constructor(
        /** PostgreSQL 风格时间戳 "2026-07-16 17:34:23.673844+08" */
        @JvmField var start: String? = null,
        @JvmField var end: String? = null,
        /** LunaBox duration 以秒为单位 */
        @JvmField var durationSeconds: Int = 0
    )
}
