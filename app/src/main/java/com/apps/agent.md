# `com.apps` UI 协作规范

本文件是 `com.apps` 后续界面改动的执行基线。先遵循现有页面的业务路径与资源命名；仅在同类组件已经重复出现时抽公共层，不为一次性界面建立平行体系。

`Launcher_UI_Specification.md` 仍负责页面、布局和命名约定；本文件补充已落地的组件、弹窗与 PadUi 规则。

## 1. 通用原则

- 保持 `LauncherActivity.wrapLauncherUiMode()`、`LauncherTheme` 与 `LauncherMotion` 的既有主题/动效链路。
- 新增普通 Activity/Fragment 使用 ViewBinding；Fragment 在 `onDestroyView()` 解除 listener 并置空 binding。
- 不以静态颜色或静态圆角 drawable 覆盖运行时主题。优先调用 `LauncherTheme`。
- 修改 UI 时只调整用户指定的层级，不顺带改动引擎启动、存档、账户同步或列表数据流。
- 竖屏与 Pad 横屏分别复用各自组件；禁止把竖屏平板缩放器直接套到 `PadUi`。

## 2. 主题色调与字体

### 主题链路

- 深/浅模式由 `LauncherActivity.applySavedToneMode()` 和 `wrapLauncherUiMode()` 决定；新 Activity 必须在 `super.onCreate()` 前应用保存的模式，并在 `attachBaseContext()` 包装 Context。
- 页面根、普通内容卡片、文本与分割线只引用 `launcher_*` 语义资源。颜色值由 `values/colors.xml` 与 `values-night/colors.xml` 分别提供，禁止在 `com.apps` 页面硬编码 `#RRGGBB`。
- 用户主题风格只改变 **primary tone**：`default`、`rinne`、`anri`、`xinhaitian`。必须通过 `LauncherTheme.primary()` / `LauncherActivity.launcherPrimaryColor()` 读取，不能直接引用默认绿 `launcher_primary_color` 作为运行时颜色。
- 心海天风格的主操作和圆形图标是双颜色渐变；因此需要主色背景时必须调用 `LauncherTheme.primaryButton()`、`circle()` 或 `primaryGradientCard()`，不能自行 new 单色 `GradientDrawable`。
- `LauncherTheme.applyPrimaryTone(root)` 负责已声明为默认主色文本、Switch/CompoundButton tint 以及已识别按钮 ID 的运行时替换。它不是任意 View 的万能着色器：新控件仍须显式调用对应主题方法，或使用现有公共 XML style。
- 所有页面创建完成、绑定数据后应对页面 root 调用 `LauncherTheme.applyPrimaryTone()`；弹窗由对应 DialogFactory 负责，不重复套竖屏缩放或背景。

### 色彩语义

| 语义 | 资源/API | 使用范围 |
|---|---|---|
| 页面背景 | `launcher_bg_color` / `launcher_bg` | 页面与沉浸式窗口背景 |
| 主内容 surface | `launcher_card_color` / `LauncherTheme.card()` | 卡片、输入框、弹窗外壳 |
| 次级 surface | `launcher_card_alt_color`、`launcher_surface_subtle_color` | 分组、弱强调区域，不替代主卡片 |
| 主文字 | `launcher_text_color` / `LauncherTheme.text()` | 标题、正文、可读主信息 |
| 次级文字 | `launcher_text_muted_color` / `LauncherTheme.textMuted()` | 描述、时间、帮助文本、空状态 |
| 主操作/选中态 | `LauncherTheme.primary()`、`primaryButton()`、`selectedChip()` | 保存、确认、当前选中、主题强调 |
| 主色上的文字 | `launcher_on_primary_color` / `onPrimary()` | 主按钮、主色 chip、渐变卡内容 |
| 危险操作 | `dangerButton()` / `dangerMenuItem()` | 删除、移除等不可逆操作；不用于普通取消 |
| 分割线 | `launcher_line_color` / `LauncherTheme.line()` | 低对比的结构分隔 |

- 次级按钮使用 `LauncherTheme.secondaryButton()`：card 色背景 + primary 色文字。
- 取消操作使用次级语义；红色仅用于明确的危险动作。不要用主色同时表达“当前状态”和“破坏性操作”。
- `launcher_game_text_overlay_color`、统计遮罩、封面/头像渐变属于内容呈现特效，不是通用页面或按钮色。

### 字体与字号层级

- 全局字体是主题定义的系统无衬线 `sans`；项目当前没有自定义品牌字体。禁止为单个新页面引入第三方字体或用 emoji 替代正文图标。
- 只有信息层级或动作需要强调时使用 `android:textStyle="bold"` / `Typeface.BOLD`；描述、正文、时间、输入内容默认 normal，避免整页粗体。

| 层级 | 规格 | 典型位置 |
|---|---|---|
| 页面主标题 | 22sp bold | 设置、编辑、管理等普通页面标题 |
| 欢迎/认证主标题 | 23–25sp bold | 注册、找回、等待页等少量引导页 |
| 弹窗/卡片标题 | 16sp bold | 普通确认弹窗、卡片内分组标题 |
| 常规操作与字段名称 | 13sp；操作按钮 bold | 按钮、标签、设置行标题 |
| 正文与输入内容 | 14sp normal | 普通表单输入、主要可读文本 |
| 描述与辅助说明 | 12–13sp normal | 帮助文案、详情说明 |
| 元信息 | 10–11sp normal | 时间、连接状态、排行榜辅助信息 |

- 聊天、排行榜、游戏封面和 Pad 导航可按现有信息密度使用专用字号；不要用它们反向定义普通表单规范。
- 需要截断的标题、游戏名和紧凑菜单标题显式设置 `singleLine + ellipsize=end`；不要通过缩小字号解决长文本。

### 动态粒子与主题色

- 首页和 Pad 横屏背景统一使用 `LauncherParticleView`；它是装饰性背景层，必须置于内容之后、不可获取焦点、不可拦截触摸，也不能放进引擎、存档或账户等业务 Activity。
- 粒子总开关与样式只通过 `LauncherActivity` 的 `launcher_particles_enabled`、`launcher_particle_style` 读写；样式限定为 `floating`、`rain`、`star`。设置页变更后调用所在页面的 `renderParticles()`，不要临时复制一套粒子状态。
- `LauncherParticleView` 在绘制时检查 `LauncherActivity.getLauncherThemeStyle()`；主题风格变化后会按粒子原有色位重新着色，不需要重建页面或重新创建动画线程。
- 默认主题保持现有的柔和彩色粒子调色板；凛弥、杏璃、心海天主题以各自 primary color 为基色，生成同色相、低饱和/分层亮度的粒子变体。粒子不得使用与当前主题无关的固定高饱和色。
- 新增主题风格时，必须同步扩展 `LauncherParticleView.particleColor()` 的取色分支，并以该主题的 primary tone 为基色生成变体；只改按钮主色、不改粒子取色视为主题未完成。
- 心海天按钮可使用主/强调色渐变，但粒子以其 primary color 为基色做亮度层次，避免背景出现大面积高对比粉色而抢占游戏卡片和文字可读性。
- 颜色变化只能更新已有粒子颜色；保留既有 56 个粒子、16ms 帧节奏、可见性/附着状态停止渲染的生命周期约束，禁止在主题切换时叠加新的 Runnable 或动画实例。

## 3. 竖屏 Launcher 组件

### 操作按钮

| 场景 | XML style | 运行时主题方法 | 固定规格 |
|---|---|---|---|
| 全宽主操作 | `LauncherLongActionButton` | `LauncherTheme.longActionButton()` | 41dp，13sp，粗体，主色 |
| 内容宽主操作 | `LauncherShortActionButton` | `LauncherTheme.shortActionButton()` | 41dp，13sp，粗体 |
| 内容宽次操作 | `LauncherShortSecondaryActionButton` | `LauncherTheme.shortSecondaryActionButton()` | 41dp，13sp，粗体 |
| 验证码等内联操作 | `LauncherInlineActionButton` | 同短按钮语义 | 不当作页面底部长按钮 |

- 并列按钮使用同一行、相同高度与明确权重；主/次/危险只改变颜色语义，不改变触控高度。
- 导航、状态 chip、筛选 chip、RecyclerView 内容项、图标按钮不是“矩形操作按钮”，不要强行套以上样式。

### 表单与列表

- 普通单行输入优先用 `LauncherTheme.formInputs()`；竖屏单行高度 45dp、正文 14sp、左右 13dp。
- 多行输入与聊天编辑器独立处理；聊天输入框不纳入普通表单规范。
- 选择器入口可复用表单外观，但保留“选择目录/封面/引擎”等业务语义，不能伪装为保存按钮。
- 列表功能行按语义区分：设置行、主题选择行、聊天入口行、游戏内容项各自保留原行高与信息层级。

## 4. 竖屏弹窗

普通 Launcher 弹窗使用 `com.apps.theme.LauncherDialogFactory`：

| 类型 | API | 宽度 |
|---|---|---|
| 普通确认 | `showConfirm()` / `showStandardConfirm()` | 252dp |
| 信息提示 | `showInfo()` | 252dp |
| 短操作菜单/单选 | `showStandardActionChoices()` / `showSingleChoice()` | 252dp |
| 长文本确认、表单 | `showLongMessageConfirm()` | 288dp |
| 长操作菜单 | `showActionChoices()` | 340dp |
| 加载、危险确认 | `showLoading()` / `showDangerConfirm()` | 252dp |

- 统一外壳为主题 card 色、20dp 圆角、透明 Window 和 `LauncherMotion.applyDialogMotion()`。
- Factory 内部会做竖屏平板缩放；不要在 `PadUi` 调用它。
- 确认回调必须先 `dismiss()` 再执行业务操作。
- 含输入法、动态进度、复杂列表或不可控长文本的弹窗不能硬迁移到普通 API；先提供有明确生命周期的专用模板，再迁移。

## 5. PadUi 横屏规范

### 布局与按钮

- `PadUi` 保持横屏信息密度，普通行内操作高度为 **38dp**、13sp、粗体、20dp 圆角、间距 8dp。
- 使用 `PadDialogFactory.primaryInlineAction()` / `secondaryInlineAction()` 为设置页的等分操作行上色；不要使用竖屏 41dp 长按钮。
- Pad 侧栏（42dp）、主题选择行、底部导航、游戏卡片工具栏、42×42 图标按钮及状态 chip 都有独立命中区语义，不与行内矩形操作按钮合并。
- Pad 紧凑输入框、Spinner 保持既有 38–40dp 高度，不套竖屏 45dp 输入框规则。

### 弹窗

普通横屏弹窗使用 `com.apps.PadUi.PadDialogFactory`：

| 类型 | API | 宽度 |
|---|---|---|
| 双按钮确认 | `showConfirm()` | 288dp |
| 普通确认、信息、加载、菜单、单选、危险确认 | 对应 `show*()` API | 270dp |
| 输入表单、详情、权限说明 | 专用实现 | 288dp |

- Factory 统一透明 Window、主题 card surface、20dp 圆角、弹窗动效，并将宽度限制为“屏幕宽度减 48dp”。
- 菜单标题必须单行省略，避免长游戏名挤压底部取消操作。
- `PadManageFragment` 的普通启动确认、游戏菜单、状态选择、更多选项、删除、同步确认/结果应继续使用 Factory；新增同类弹窗不要手写 root/title/button。
- `PadSettingsActivity` 的账户确认、账户加载、结果提示应继续使用 Factory；设置页的三等分操作按钮通过 inline API 统一。

### 必须保留专用实现的场景

- 修改游玩时长：使用 `Dialog`、保留 `SOFT_INPUT_STATE_VISIBLE`、焦点和 IME 唤起。
- 同步进度：非取消、保留 `sync_progress` tag 与后台更新链。
- 文件访问权限：保留 Android 版本分支和系统设置跳转。
- 游戏详情：保留 288dp 详情容器；长 URI/包名改动前先确认滚动与截断策略。
- 粒子样式选择：保留当前已选状态、关闭粒子操作和 Toast 反馈；迁移前必须等价表达这些状态。

## 6. 修改与验证清单

1. 先查找同类页面/组件，复用现有公共方法。
2. 保证运行时仍调用 `LauncherTheme`，不把主题色写死到 XML 或 Java。
3. 对确认、删除、同步、启动操作确认回调顺序为：关闭弹窗 → 执行业务。
4. 检查横屏系统栏 inset、刘海/药丸屏安全区，以及滚动区域不被固定导航覆盖。
5. 最低验证：

```bash
./gradlew :app:assembleDebug
git diff --check
```

6. 涉及 IME、同步、权限、引擎启动或真机现象时，再按对应业务路径做实机验证；不要以 UI 编译成功替代行为验证。
