# Launcher UI Agent 规范

本文件约束 `app/src/main/java/com/apps` 下新增 Launcher 界面、弹窗、卡片与交互组件的样式实现方式。

## 目标

- 保持 Launcher 新增页面与首页、游戏库、管理页的视觉一致性。
- 主题切换只走 Launcher 自己的主题层，不依赖主项目系统样式。
- 优先复用现有主题 token、drawable、样式、弹窗骨架，减少单独编码。

## 颜色与主题

- 禁止在新增 Launcher 页面、弹窗、drawable 中直接写业务色十六进制。
- 优先使用 `app/src/main/res/values/colors.xml` 与 `values-night/colors.xml` 中的 `launcher_*` token。
- 主题主色统一通过 `LauncherTheme.primary(context)` 获取；不要直接把 `@color/launcher_primary_color` 当成运行时最终色。
- 危险操作统一使用 `LauncherTheme.danger(...)` / `LauncherTheme.dangerButton(...)`，不要手写红色。
- 允许保留少量功能/品牌辅助色，但必须先抽成 `launcher_accent_*` 或 `launcher_brand_*` token，再引用资源，不直接写死。

## 按钮

- 主按钮统一使用 `LauncherTheme.primaryButton(TextView)` 或 `LauncherTheme.primaryButton(context, radiusDp)`。
- 次按钮、取消按钮、菜单项统一使用 `LauncherTheme.secondaryButton(TextView)` 或 `LauncherTheme.menuItem(TextView)`。
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
- 弹窗按钮顺序统一为：左取消、右确认；危险确认用 Launcher 危险态按钮。

## 图标容器

- 圆形图标容器优先使用 `LauncherTheme.circle(...)` 或现有 `launcher_round_icon_*` token 化资源。
- 工具箱、聊天选择、主题菜单等功能色图标允许使用辅助色，但必须走 `launcher_accent_*` token。
- 新增图标容器不要直接把颜色写进 layout。

## Activity / Fragment 接入要求

- 新增 Launcher 页面默认在 `onCreate` / `onViewCreated` 里调用 `LauncherActivity.applySavedToneMode(...)`。
- 根布局或关键组件必须接入 `LauncherTheme.applyPrimaryTone(...)`，确保主题切换完整覆盖。
- Edge-to-edge、状态栏、导航栏处理优先复用现有 Launcher Activity 模板写法。

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
