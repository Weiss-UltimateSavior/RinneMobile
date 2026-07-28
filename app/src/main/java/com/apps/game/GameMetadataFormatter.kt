package com.apps.game

import android.content.Context
import android.text.TextUtils
import com.core.R
import com.core.model.EngineType
import com.core.model.Game
import java.util.Locale
import java.util.regex.Pattern

/**
 * 游戏元数据格式化与解析的纯静态工具集。
 *
 * 来源：LauncherLibraryFragment / PadManageFragment / LauncherGameActionController 三处重复实现。
 * 全部为无状态纯函数，可被任意 Fragment / Controller / Adapter 调用。
 */
object GameMetadataFormatter {

    /** 返回可读的游玩状态文本（未玩/在玩/玩过），null 或未知值统一返回"未玩"。 */
    @JvmStatic
    fun playStatusText(status: String?): String = when (status) {
        "playing" -> "Playing"
        "completed" -> "Completed"
        else -> "Not started"
    }

    @JvmStatic
    fun playStatusText(context: Context, status: String?): String = context.getString(
        when (status) {
            "playing" -> R.string.game_status_playing
            "completed" -> R.string.game_status_completed
            else -> R.string.game_status_unplayed
        }
    )

    /** 返回可读的引擎名称；null 或未知值返回"未知"。 */
    @JvmStatic
    fun engineText(engine: EngineType?): String = when (engine) {
        EngineType.KIRIKIRI -> "Kirikiri"
        EngineType.ONS -> "ONS"
        EngineType.TYRANO -> "Tyrano"
        EngineType.ARTEMIS -> "Artemis"
        EngineType.WINLATOR -> "Winlator"
        EngineType.GAMEHUB -> "GameHub"
        EngineType.PSP -> "PSP"
        EngineType.NINTENDO_3DS -> "3DS"
        else -> "Unknown"
    }

    @JvmStatic
    fun engineText(context: Context, engine: EngineType?): String =
        if (engine == null || engine == EngineType.UNKNOWN) {
            context.getString(R.string.game_common_unknown)
        } else {
            engineText(engine)
        }

    /**
     * 解析时长字符串为分钟数。
     * 支持 d/h/m/s 单位组合（如 "3h 20m"），纯数字视为分钟。
     * 解析失败或为空时返回 null。
     */
    @JvmStatic
    fun parseDuration(text: String?): Long? {
        if (text == null || text.trim { it <= ' ' }.isEmpty()) return null
        val normalized = text.trim { it <= ' ' }.lowercase(Locale.ROOT)
        return try {
            if (!normalized.matches(".*[dhms].*".toRegex())) {
                return normalized.toDouble().toLong()
            }
            var total = 0L
            val m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])").matcher(normalized)
            var found = false
            while (m.find()) {
                found = true
                val v = m.group(1)!!.toDouble()
                val delta = when (m.group(2)) {
                    "d" -> v * 1440
                    "h" -> v * 60
                    "m" -> v
                    "s" -> v / 60
                    else -> 0.0
                }
                total = (total + delta).toLong()
            }
            if (found) total else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 归一化游玩状态字符串为服务端可识别的三种值：playing / completed / unplayed。
     * null 或空串归一化为 unplayed。
     */
    @JvmStatic
    fun normalizePlayStatus(status: String?): String {
        val value = status?.trim { it <= ' ' }?.lowercase(Locale.ROOT) ?: ""
        return if (value == "completed" || value == "playing") value else "unplayed"
    }

    /**
     * 解析开发商字段为列表。null / 空 / "-" 返回空列表。
     * 按 / 、 , ， 切分，去重并 trim。
     */
    @JvmStatic
    fun parseDevelopers(developersText: String?): List<String> {
        if (developersText == null || developersText.trim { it <= ' ' }.isEmpty()
            || "-" == developersText.trim { it <= ' ' }
        ) {
            return emptyList()
        }
        return developersText.split("/|、|,|，".toRegex())
            .map { it.trim { it <= ' ' } }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 返回游戏标题；空或缺失时返回"未命名游戏"。 */
    @JvmStatic
    fun safeTitle(game: Game?): String {
        if (game == null || TextUtils.isEmpty(game.title)) return "Game"
        return game.title!!.trim { it <= ' ' }
    }

    @JvmStatic
    fun safeTitle(context: Context, game: Game?): String {
        if (game == null || TextUtils.isEmpty(game.title)) {
            return context.getString(R.string.game_unnamed)
        }
        return game.title!!.trim { it <= ' ' }
    }
}
