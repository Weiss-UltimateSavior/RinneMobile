package com.apps

/**
 * Launcher 启动相关 Intent action 与 Extra 键常量单源对象。
 *
 * 该 object 从 LauncherActivity 的 companion object 中抽取而来，统一托管与
 * Launcher 启动跳转相关的 intent action 与 extra 键名，供各组件跨类共享，
 * 避免 Activity companion object 承担全局静态职责。常量值与原实现完全一致。
 */
object LauncherIntents {

    /** 通过 Intent extra 传递：启动后自动打开账号登录页，值为 Boolean。 */
    const val EXTRA_OPEN_ACCOUNT_LOGIN = "open_account_login"

    /** 通过 Intent extra 传递：启动后直达指定固定（pinned）游戏，值为 game id。 */
    const val EXTRA_PINNED_GAME_ID = "pinned_game_id"

    /** 通过 Intent extra 传递：强制以竖屏主页进入，值为 Boolean。 */
    const val EXTRA_FORCE_PORTRAIT_HOME = "force_portrait_home"

    /** 启动固定游戏的 Intent action。 */
    const val ACTION_LAUNCH_PINNED_GAME = "com.core.action.LAUNCH_PINNED_GAME"

    /** 包名重构前遗留的启动固定游戏 Intent action，用于兼容旧桌面快捷方式。 */
    const val LEGACY_ACTION_LAUNCH_PINNED_GAME = "com.yuki.yukihub.action.LAUNCH_PINNED_GAME"
}
