package com.yuki.yukihub.launcherbridge;

/**
 * 免责声明内容桥接层。
 * 统一持有免责声明文本，供 Launcher 直接获取后在新 Activity 中展示，
 * 避免跳转到 MainActivity 的弹窗。
 */
public final class LauncherDisclaimerBridge {
    private LauncherDisclaimerBridge() {
    }

    public static String getTitle() {
        return "免责声明";
    }

    public static String getContent() {
        return "1. 本应用为开源项目，仅用于管理、整理和启动用户本人有权使用的游戏与应用。\n\n" +
                "2. 用户应自行确保所添加资源、账号、同步内容以及第三方服务的合法性、完整性与可用性。\n\n" +
                "3. 本应用不提供任何游戏本体、破解资源、绕过授权或规避版权/平台规则的能力。\n\n" +
                "4. Shizuku、GameHub、WebDAV、VNDB、Bangumi、系统存储权限等能力均依赖第三方应用、系统环境或外部服务，可能因设备、系统版本、权限状态或服务变更而不可用。\n\n" +
                "5. 因第三方服务、系统限制、用户误操作或资源本身问题造成的数据丢失、同步异常、启动失败、兼容性问题或其他损失，开发者不承担额外责任。\n\n" +
                "6. 如果你不同意以上说明，请停止使用相关功能。";
    }
}
