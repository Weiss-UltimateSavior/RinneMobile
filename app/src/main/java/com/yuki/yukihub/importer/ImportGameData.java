package com.yuki.yukihub.importer;

import java.util.List;
import java.util.Map;

/**
 * 三方平台导入时的单条游戏数据（中间格式）。
 *
 * 各 Importer 把原始格式（Playnite JSON / PotatoVN ZIP / Vnite 目录 / LunaBox ZIP）
 * 解析成 ImportGameData，再由 ImporterService 统一写库。
 *
 * 字段命名与 {@link com.yuki.yukihub.model.Game} 对齐，便于在 Service 层直接映射。
 */
public class ImportGameData {

    /** 游戏标题（显示名） */
    public String name;
    /** 原始标题（如有） */
    public String originalName;
    public String developer;
    public String description;
    /** 远程封面 URL（http/https） */
    public String coverUrl;
    /** 本地封面路径（ZIP 内解压的文件） */
    public String coverLocalPath;
    /** YYYY-MM-DD */
    public String releaseDate;
    public double rating;
    /** 游戏路径（Windows 路径，Android 上可能无效） */
    public String path;
    public String savePath;
    /** vndb / bangumi / ymgal / steam / local */
    public String sourceType;
    public String sourceId;
    public List<String> tags;
    public long createdAt;
    /** 总游戏时长（秒） */
    public long totalPlayTime;
    /** 游玩状态：unplayed / playing / completed（来自 LunaBox status 映射） */
    public String playStatus;

    // ===== 预览阶段字段 =====
    /** 是否已有同标题游戏（预览标记） */
    public boolean exists;
    /** 用户是否勾选导入此条 */
    public boolean selected;
    /** 如果已存在，标记原因 */
    public String conflictReason;

    // ===== 各平台游玩记录原始结构 =====

    /** PotatoVN PlayedTime: date -> minutes，由 ImporterService 转成 play_sessions */
    public Map<String, Integer> playedTimeMap;

    /** Vnite Timers: 每条有 start / end 时间字符串 */
    public List<VniteTimer> vniteTimers;

    /** LunaBox Sessions: 每条有 start / end 时间字符串 + duration(秒) */
    public List<LunaBoxSession> lunaBoxSessions;

    /** Vnite 计时器条目。 */
    public static class VniteTimer {
        public String start;
        public String end;
    }

    /** LunaBox 游玩会话条目。 */
    public static class LunaBoxSession {
        /** PostgreSQL 风格时间戳 "2026-07-16 17:34:23.673844+08" */
        public String start;
        public String end;
        /** LunaBox duration 以秒为单位 */
        public int durationSeconds;
    }
}
