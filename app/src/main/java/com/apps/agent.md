# Launcher UI Agent 规范

本文件约束 `app/src/main/java/com/apps` 下新增 Launcher 界面、弹窗、卡片与交互组件的样式实现方式。

## 目标

- 保持 Launcher 新增页面与首页、游戏库、管理页的视觉一致性。
- 主题切换只走 Launcher 自己的主题层，不依赖主项目系统样式。
- 优先复用现有主题 token、drawable、样式、弹窗骨架，减少单独编码。

## 当前项目基线（2026-07-13）

### Launcher 入口与页面职责

- `LauncherActivity` 是竖屏 Launcher 容器，负责 edge-to-edge、主题恢复、粒子背景、底部导航、自动更新检查与 Fragment 切换。
- 底部导航页面固定为：`HOME` → `LauncherHomeFragment`、`LIBRARY` → `LauncherLibraryFragment`、`MANAGE` → `LauncherManageFragment`、`ACCOUNT` → `LauncherAccountFragment`。页面选择状态由共享的 `LauncherViewModel` 持有。
- 中央启动入口打开 `PadUi/PadGameModeActivity`；横屏模式由 `PadGameFragment`、`PadManageFragment` 和 `PadSettingsActivity` 分别承载游戏、仓库与设置功能。
- `LauncherLibraryFragment` 是竖屏游戏库基类，搜索、分类、分页、启动与同步相关能力应优先在此复用；`PadManageFragment` 是独立的横屏仓库实现，不要再假定其继承该类。

### 首页交互

- 首页统计卡片不再每 3 秒轮询。`SwipeRefreshLayout` 下拉刷新必须同时调用 `LauncherViewModel.refreshStats()` 与 `refreshRecentItems(true)`；不要恢复定时刷新。
- 右上角弹出菜单的**容器**使用 `LauncherTheme.primaryButton(context, 18f)` 跟随主题主色；菜单项保持透明，文字使用 `LauncherTheme.onPrimary(context)`，并采用 `Gravity.CENTER` 居中。不要将每个菜单项做成独立主按钮。
- 首页“资讯站”入口打开 `ResourceStationActivity`。弹窗标题、入口文案和默认标题统一使用“资讯站”；现有条目包括“聚合搜索”（`https://searchgal.top`）、鲲 Galgame、真红小站与 Touch Gal。新增站点继续通过 `resource_url`、`resource_title` 传给该 Activity。

### 导航与性能约束

- `LauncherActivity.moveNavIndicator(...)` 负责底部导航指示器。导航栏已布局时必须立即启动动画；仅在未布局时才回退到 `post(...)`。
- 指示器动画应在硬件层运行，并在启动新动画前取消旧动画，避免 `LauncherLibraryFragment` 创建、布局或入场动画抢占首帧而造成卡顿。
- 游戏库和横屏仓库是性能敏感区域：保持 `RecyclerView` 的稳定 ID、禁用不必要的 item animator，分页位移动画只在动画期间使用硬件层；不要在尺寸变化或翻页时无条件 `notifyDataSetChanged()`。
- 封面重绑时，如果 URI 未变化，保留现有 drawable，不要先清空图片再重载。

### 适配与构建

- 新增 Launcher Activity 在设置内容视图后调用 `LauncherTabletPortraitScaler.applyActivityContent(this)`；Fragment 根布局和动态列表项调用 `LauncherTabletPortraitScaler.apply(...)`。
- 当前 `app` 模块使用 Min SDK 26、Target SDK 33、Compile SDK 36、Java 17 与 Android Gradle Plugin 8.13.2。新增代码不得引入低于 API 26 的兼容分支需求。

## 全项目架构与核心链路

### 模块边界

- `app` 是业务模块：包含 Launcher、游戏库、扫描、元数据、WebDAV 同步、网络客户端、游戏启动策略，以及 `launcherbridge` 适配层。
- `engine` 是内置运行时模块：封装 KRKR、Tyrano、Artemis、ONScripter 和相关 native `.so` / 资源。它不是普通 UI 依赖，修改时必须兼顾独立进程、Activity 声明和 native 库打包。
- `com.apps` 只承载 Launcher 与 PadUi 体验层；通过 `com.yuki.yukihub.launcherbridge.*` 调用核心能力。新增 Launcher 功能不得绕过 bridge 直接拼接数据库 SQL 或复制核心业务逻辑。
- 应用入口是 `com.apps.LauncherActivity`。内置引擎 Activity、`PadGameModeActivity` 与所有 Launcher 子页面均在 `AndroidManifest.xml` 显式声明；新增页面或引擎入口时必须同步检查导出状态、屏幕方向、进程和主题。

### 领域模型、数据库与游玩记录

- `Game` 是游戏条目的唯一领域模型；关键字段为 `engine`、`rootUri`、`launchTarget`、`emulatorPackage`、封面信息、GameHub / Winlator 启动参数与游玩统计。
- `YukiDatabaseHelper` 当前 schema 版本为 13，维护 `games`、`play_sessions`、`metadata_cache`、`settings` 四张表，并在每次打开数据库时启用外键。
- `GameRepository.normalizeRootUriKey(...)` 和 `games.root_uri_key` 的部分唯一索引是跨扫描、导入和恢复的稳定身份。插入和更新必须经由 `GameRepository`，不要用原始 URI 或本地自增 ID 判断跨设备同一游戏。
- `play_sessions.session_uuid` 是游玩记录的跨设备身份；结算必须走 `startPlaySession(...)` → `finishPlaySession(...)` 或 `LauncherGameLaunchBridge.finishSession(...)`，不要由 UI 直接累计 `total_play_time`。
- 旧版 `LauncherRepositoryBridge.importPlaySql(...)` 仅用于受控的完整本地恢复。跨设备同步和新功能必须使用 JSON 快照与 `GameRepository.importGamesJson(...)` / `importPlaySessionsJson(...)` 的身份合并逻辑，禁止重新引入按 raw ID 的 `INSERT OR REPLACE` 恢复。

### 扫描、导入与元数据

- 扫描入口为 `GameScanner`，目录特征判定集中在 `EngineDetector`；支持 Kirikiri、ONScripter、Tyrano、Artemis、Winlator `.desktop`、GameHub 和 PSP（ISO/CSO/CHD/ELF/PBP）。
- 扫描深度与“候选目录内特征探测深度”是两层概念。新增扫描入口应复用现有深度选择流程，不能偷偷回到固定深度扫描。
- 发现多个 XP3 候选入口时必须让用户确认，不得按文件系统顺序猜测启动文件。
- 元数据读写通过 `MetadataRepository`、`VndbClient`、`BangumiClient` 与 `LauncherMetadataBridge` 集中处理；`metadata_cache` 按 `(game_id, source)` 唯一，更新时要保留来源与更新时间。
- PSP 现阶段是外部 PPSSPP 启动与扩展名扫描；如未来接入 `PARAM.SFO` / `ICON0.PNG`，只应在扫描结果入库前补全标题、disc ID 和封面，并复用已有封面持久化路径，不在扫描阶段启动模拟器。

### 启动策略与内置引擎

- 启动主链为 `LauncherGameLaunchBridge` → `EmulatorLauncher` → `EngineLaunchStrategy` / 对应 Intent。`LaunchRequest` 是策略层的不可变输入；新增引擎需新增明确策略，而不是把特殊逻辑散落在页面事件中。
- 内置 KRKR、Tyrano、ONS、Artemis 使用应用内 Activity；PSP 目前依赖已安装的 PPSSPP；Winlator 和 GameHub 采用包名及各自的启动参数。策略识别成功但启动失败时应返回失败，不得悄悄回退到模拟器首页。
- 内置引擎大多运行在独立进程（如 `:kirikiri2`、`:tyrano`、`:artemis`、`:ons`）。涉及进程、task affinity、native 库或启动 Intent 的改动，必须以真机启动对应引擎验收。

### 同步、网络与安全边界

- `SyncManager` 负责 WebDAV JSON 快照、冲突处理和导入事务；远端快照上限 16 MiB、本地备份上限 32 MiB，远端只保留最近 200 条游玩会话。不要移除大小限制或把本地扫描目录、文件 URI、私有背景路径同步到云端。
- `WebDavClient` 使用用户提供的服务器与 Basic Auth；应用为兼容现有 WebDAV 服务保留明文网络能力。新增账号、同步或远程资源功能默认要求 HTTPS，并在允许 `http://` 时明确提示凭据传输风险。
- `HttpClient` 是 Retrofit / OkHttp 的公共入口，统一 User-Agent、超时和响应体关闭。网络调用必须放在 `AppExecutors` 等后台执行器中，UI 仅接收结果。
- Tyrano 内置 WebView 使用本地 HTTP 服务和 JavaScript bridge。不要让任意外部 URL 获得 bridge 访问权，也不要扩大 file / universal access 的作用范围。

### 验证与发布前检查

- 当前仓库未发现 `app/src/test` 或 `app/src/androidTest` 下的自动化测试；修改核心数据、扫描、同步或启动链路时，应优先补充可离线执行的单元测试。
- 最低验证命令：`./gradlew :app:compileDebugJavaWithJavac`、`git diff --check`；涉及资源、Manifest、依赖或安全配置时再运行 `./gradlew :app:lintDebug`。
- 当前 lint 会报告 Tyrano WebView 图层类型的硬编码常量问题；不要通过关闭 lint 或建立空 baseline 掩盖它。后续修复应改用 Android 的 `View.LAYER_TYPE_*` 常量，并重新跑完整 lint。

## 颜色与主题

- 禁止在新增 Launcher 页面、弹窗、drawable 中直接写业务色十六进制。
- 优先使用 `app/src/main/res/values/colors.xml` 与 `values-night/colors.xml` 中的 `launcher_*` token。
- 主题主色统一通过 `LauncherTheme.primary(context)` 获取；不要直接把 `@color/launcher_primary_color` 当成运行时最终色。
- 主题前景色通过 `LauncherTheme.onPrimary(context)` 获取；当背景已经是主题主色或 `primaryButton(...)` 时，不能继续使用主色文字。
- 当前主题风格包括默认、Rinne、Anri、鑫海天；鑫海天的主按钮由 `LauncherTheme.primaryButton(...)` 输出渐变，因此不要自行以纯色覆盖它。
- 危险操作统一使用 `LauncherTheme.danger(...)` / `LauncherTheme.dangerButton(...)`，不要手写红色。
- 允许保留少量功能/品牌辅助色，但必须先抽成 `launcher_accent_*` 或 `launcher_brand_*` token，再引用资源，不直接写死。

## 按钮

- 主按钮统一使用 `LauncherTheme.primaryButton(TextView)` 或 `LauncherTheme.primaryButton(context, radiusDp)`。
- 次按钮、取消按钮、普通弹窗选项统一使用 `LauncherTheme.secondaryButton(TextView)` 或 `LauncherTheme.menuItem(TextView)`。
- 若弹出菜单容器已使用主题主色，菜单项本身应保持透明，并使用 `LauncherTheme.onPrimary(...)`；不要再套 `menuItem(...)`。
- 危险按钮统一使用 `LauncherTheme.dangerButton(TextView)` 或 `LauncherTheme.dangerMenuItem(TextView)`。
- 不要仅在 XML 里写 `@drawable/launcher_account_primary_button` 后就结束；对应 Activity/Fragment 还要接入 `LauncherTheme.applyPrimaryTone(...)` 或显式套用运行时 helper，保证切换主题后同步变色。

## 输入框与 Spinner

- 文本输入框优先复用 `launcher_account_input`、`LauncherAddGameInput`、`LauncherAddGameInputText`。
- 新增表单不要自造另一套圆角、描边、内边距。
- Spinner 统一使用 `LauncherTheme.spinnerAdapter(...)` + `LauncherTheme.styleSpinner(...)`。
- 不要使用 `android.R.layout.simple_spinner_item` / `simple_spinner_dropdown_item`。

## 卡片与列表

- 白色卡片容器统一优先复用 `launcher_white_card`。
- 选择态标签统一复用 `LauncherTheme.selectedChip(...)`、`LauncherTheme.chip(...)`、`launcher_filter_chip_unselected`。
- 列表项的标题、说明、辅助信息分别使用 `launcher_text_color`、`launcher_text_muted_color`、主题辅助 token。
- 新增游戏卡片、列表卡片、设置卡片时，圆角、边距、遮罩先向现有 Launcher 卡片对齐，不要引入主项目深色玻璃风资源。

## 弹窗

- Launcher 内新弹窗统一沿用 `dialog_launcher_confirm.xml` 或 `LauncherLibraryFragment` / `LauncherToolboxActivity` 现有弹窗骨架。
- 弹窗背景用 `launcher_dialog_bg` 或 `launcher_white_card` 风格，不再新增独立视觉体系。
- 横向确认弹窗按钮顺序统一为：左取消、右确认；危险确认用 Launcher 危险态按钮。纵向选项弹窗可按“选项在前、取消在后”的现有 Launcher 模式组织。

## 图标容器

- 圆形图标容器优先使用 `LauncherTheme.circle(...)` 或现有 `launcher_round_icon_*` token 化资源。
- 工具箱、聊天选择、主题菜单等功能色图标允许使用辅助色，但必须走 `launcher_accent_*` token。
- 新增图标容器不要直接把颜色写进 layout。

## Activity / Fragment 接入要求

- 新增 Launcher **Activity** 必须在 `super.onCreate(...)` 前调用 `LauncherActivity.applySavedToneMode(this)`；Fragment 由宿主 Activity 恢复色调，无需重复切换夜间模式。
- 根布局或关键组件必须接入 `LauncherTheme.applyPrimaryTone(...)`，确保主题切换完整覆盖。
- 新增 Activity 和 Fragment 均须接入 `LauncherTabletPortraitScaler`，动态创建的对话框内容或列表项也要一并处理。
- Edge-to-edge、状态栏、导航栏处理优先复用现有 Launcher Activity 模板写法。

## 动效与页面跳转

- Launcher 内新增动效统一走 `LauncherMotion`，不要在各个 Activity / Fragment 中重复手写 `overridePendingTransition(...)`、弹窗动画或点击缩放动画。
- 打开新的 Launcher Activity 后，统一调用 `LauncherMotion.applyActivityOpen(activity)`。
- 关闭 Launcher Activity 时，优先使用 `LauncherMotion.finish(activity)`；如必须手动 `finish()`，结束后也要调用 `LauncherMotion.applyActivityClose(activity)`。
- Launcher 内弹窗创建并 `show()` 后，统一调用 `LauncherMotion.applyDialogMotion(dialog)`，保证弹窗进入 / 退出动画一致。
- 主题 / 色调切换需要 `recreate()` 页面时，统一使用 `LauncherMotion.recreateWithToneOverlay(activity, beforeRecreate)`，不要直接裸调用 `activity.recreate()`。
- Fragment 切换统一使用 `launcher_fragment_enter` / `launcher_fragment_exit` 动画资源，不新增另一套 Fragment 动画。
- 底部导航由 `LauncherActivity` 统一驱动；不要在各 Fragment 内直接替换 Launcher 主容器或另写一套指示器动画。
- Activity 打开 / 关闭统一使用 `launcher_activity_enter`、`launcher_activity_exit`、`launcher_activity_pop_enter`、`launcher_activity_pop_exit`。
- 弹窗动画统一使用 `LauncherDialogAnimation`，对应资源为 `launcher_dialog_enter` / `launcher_dialog_exit`。
- 禁止新增页面单独定义一套与 `LauncherMotion` 并行的动效工具类，除非是特殊组件内部动画，且不影响页面跳转、弹窗、主题切换的统一规范。

## 禁止事项

- 不修改主项目原有业务页面来迁就 Launcher 样式。
- 不在 Launcher 新增界面里混用 `yh_*` 深色主项目 token，除非该页面明确就是主项目风格桥接页。
- 不新增一套与 Launcher 已有按钮、输入框、弹窗并行的重复组件。

## 新增页面自检清单

- 是否只使用了 `launcher_*` token 或经过抽象的辅助色 token。
- 是否没有 `#RRGGBB` / `0xFF...` 直接写在 Launcher 新代码里。
- 是否主按钮、次按钮、危险按钮都走了 `LauncherTheme`。
- 是否 Spinner、输入框、弹窗、卡片都复用了现有 Launcher 资源。
- 是否切换浅色 / 深色 / Launcher 主题风格后，按钮、图标、文本、遮罩会同步变化。
- 是否在平板竖屏下使用 `LauncherTabletPortraitScaler`，且不破坏横屏 PadUi 的独立布局。
- 是否避免在游戏库/仓库的滚动、分页、封面重绑路径中引入全量重绘或不必要的 item 动画。
- 首页下拉刷新是否同时更新统计卡片与最近游玩列表，且没有恢复 3 秒轮询。
- 页面跳转是否使用了 `LauncherMotion.applyActivityOpen(...)` / `LauncherMotion.finish(...)`。
- 弹窗是否调用了 `LauncherMotion.applyDialogMotion(dialog)`。
- 主题切换是否通过 `LauncherMotion.recreateWithToneOverlay(...)` 完成。
