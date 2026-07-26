package com.apps.game;

import android.text.TextUtils;

import com.core.model.EngineType;
import com.core.model.Game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 游戏元数据格式化与解析的纯静态工具集。
 *
 * 来源：LauncherLibraryFragment / PadManageFragment / LauncherGameActionController 三处重复实现。
 * 全部为无状态纯函数，可被任意 Fragment / Controller / Adapter 调用。
 */
public final class GameMetadataFormatter {

    private GameMetadataFormatter() {
    }

    /** 返回可读的游玩状态文本（未玩/在玩/玩过），null 或未知值统一返回"未玩"。 */
    public static String playStatusText(String status) {
        if (status == null) return "未玩";
        switch (status) {
            case "playing":
                return "在玩";
            case "completed":
                return "玩过";
            default:
                return "未玩";
        }
    }

    /** 返回可读的引擎名称；null 或未知值返回"未知"。 */
    public static String engineText(EngineType engine) {
        if (engine == null) return "未知";
        switch (engine) {
            case KIRIKIRI:
                return "Kirikiri";
            case ONS:
                return "ONS";
            case TYRANO:
                return "Tyrano";
            case ARTEMIS:
                return "Artemis";
            case WINLATOR:
                return "Winlator";
            case GAMEHUB:
                return "GameHub";
            case PSP:
                return "PSP";
            case NINTENDO_3DS:
                return "3DS";
            default:
                return "未知";
        }
    }

    /**
     * 解析时长字符串为分钟数。
     * 支持 d/h/m/s 单位组合（如 "3h 20m"），纯数字视为分钟。
     * 解析失败或为空时返回 null。
     */
    public static Long parseDuration(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        text = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (!text.matches(".*[dhms].*")) return (long) Double.parseDouble(text);
            long total = 0;
            Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])").matcher(text);
            boolean found = false;
            while (m.find()) {
                found = true;
                double val = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                if (unit.equals("d")) total += val * 1440;
                else if (unit.equals("h")) total += val * 60;
                else if (unit.equals("m")) total += val;
                else if (unit.equals("s")) total += val / 60;
            }
            return found ? total : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 归一化游玩状态字符串为服务端可识别的三种值：playing / completed / unplayed。
     * null 或空串归一化为 unplayed。
     */
    public static String normalizePlayStatus(String status) {
        String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) || "playing".equals(value)) return value;
        return "unplayed";
    }

    /**
     * 解析开发商字段为列表。null / 空 / "-" 返回空列表。
     * 按 / 、 , ， 切分，去重并 trim。
     */
    public static List<String> parseDevelopers(String developersText) {
        List<String> result = new ArrayList<>();
        if (developersText == null || developersText.trim().isEmpty()
                || "-".equals(developersText.trim())) return result;
        String[] parts = developersText.split("/|、|,|，");
        for (String raw : parts) {
            String developer = raw == null ? "" : raw.trim();
            if (!developer.isEmpty() && !result.contains(developer)) result.add(developer);
        }
        return result;
    }

    /** 返回游戏标题；空或缺失时返回"未命名游戏"。 */
    public static String safeTitle(Game game) {
        if (game == null || TextUtils.isEmpty(game.title)) return "未命名游戏";
        return game.title.trim();
    }
}
