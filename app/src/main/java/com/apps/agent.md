# `com.apps` UI 协作规范

本文件是 `com.apps` 后续界面改动的执行基线。先遵循现有页面的业务路径与资源命名；仅在同类组件已经重复出现时抽公共层，不为一次性界面建立平行体系。

`Launcher_UI_Specification.md` 仍负责页面、布局和命名约定；本文件补充已落地的组件、弹窗与 PadUi 规则。

## 1. 通用原则

- 保持 `LauncherActivity.wrapLauncherUiMode()`、`LauncherTheme` 与 `LauncherMotion` 的既有主题/动效链路。
- 主题/色调/偏好等全局静态 API 的主源已落在独立 `object`（`LauncherPreferences`、`LauncherThemeStyle`、`LauncherUiMode`、`LauncherSplash`、`LauncherNavigationMetrics`）；`LauncherActivity` 的 companion 仅保留 `@JvmStatic` 委托方法与兼容常量，供既有 Java/Kotlin 调用方零修改。新增静态 API 直接写入对应 object，不再向 `LauncherActivity` companion 添加实现。
- 新增普通 Activity/Fragment 使用 ViewBinding；Fragment 在 `onDestroyView()` 解除 listener 并置空 binding。
- 不以静态颜色或静态圆角 drawable 覆盖运行时主题。优先调用 `LauncherTheme`。
- 修改 UI 时只调整用户指定的层级，不顺带改动引擎启动、存档、账户同步或列表数据流。
- 竖屏与 Pad 横屏分别复用各自组件；禁止把竖屏平板缩放器直接套到 `PadUi`。

## 2. 新增功能代码规范

### 语言与文件选择

- 新增 Activity / Fragment 一律使用 Kotlin + XML 布局；不再新建 Java 页面文件。
- 新增业务功能方法（Bridge、Controller、Manager、Repository、工具类等）一律使用 Kotlin。
- 已存在的 Java 文件可在原文件内继续修改；只有大规模重构时才迁移到 Kotlin
- 新增 Kotlin 业务文件放在与职责对应的 `com.core.*` 子包下；UI 页面放在 `com.apps.*` 对应子包。

### 页面三语与本地化

- 页面中所有用户可见文案必须资源化：XML 使用 `@string/...`，Kotlin/Java 使用当前页面或传入 `Context` 的 `getString()`；禁止在 Activity、Fragment、Adapter、Dialog、Toast、加载页或引擎壳层硬编码中文、英文或日文。
- 每个新增或修改的用户可见字符串，必须同时维护 `res/values/strings.xml`、`res/values-en/strings.xml` 与 `res/values-ja/strings.xml` 中的同名 key。简中、英文、日文缺任一项，视为页面未完成。
- 页面展示的动态文字保存语义原值（状态、类型、资源 key、枚举或原始数据），在绑定/渲染时按当前 `Context` 解析为三语文案；不得在 Repository、ViewModel、单例或 Adapter 数据中缓存已经翻译的字符串，以保证切换语言后立即刷新。
- 跨进程或内置引擎页面必须通过 `Intent` 明确传递当前语言 tag，并在目标进程基于该 tag 创建本地化 Context 后读取资源；不得依赖旧页面、Application Context 或进程启动时缓存的 Locale。
- 数据库字段、Intent extra key、偏好设置 key、网络协议、游戏目录标记和引擎类型等机器可读值保持稳定原文，不得翻译；只在 UI 层为其提供本地化展示名称。
- 语言切换完成后，当前可见页面、列表项、在线状态、加载遮罩和弹窗都应重新读取资源并刷新；验证时至少检查简中、英文、日文三种语言，以及页面旋转或重建后的显示。

### Kotlin 代码风格

- 工具类 / 桥接类使用 `object` + `@JvmStatic` 暴露入口给 Java 调用方；常量使用 `const val`；包内可见方法使用 `internal`。
- 跨 Java 边界的方法参数优先声明为可空（如 `String?`），兼容 Java 调用方传入 null；保留既有约定（参考 `VndbClient.kt`、`MetadataUtils.cleanTitle()`）。
- 需要复刻 Java `trim()` 语义时使用 `uriText == null || uriText.trim().isEmpty()`，不用 `isNullOrBlank()`（参考 `SafeImageLoader.kt`）。
- 需要匹配 Java `Math.round(float)` 半向上语义时使用 `Math.round(float)`，不用 `roundToInt()`（参考 `UiScaleUtil.kt`）。
- 集合优先使用只读 `List` / `Map`，需要可变时显式使用 `MutableList` / `MutableMap`。
- 不滥用 `!!`；可空值用 `?.` 或 `?:` 兜底。
- 单方法接口使用 `fun interface`；Java 枚举迁移使用 `enum class` + `companion object` + `@JvmStatic fromString`。
- `@Volatile` 仅用于跨线程可见性需求；只在 `@Synchronized` 方法内访问的字段不需要 `@Volatile`。

### XML 布局规范

- 新增布局文件命名沿用 `snake_case`：`activity_xxx`、`fragment_xxx`、`dialog_xxx`、`item_xxx`、`view_xxx`。
- 优先复用现有 style、`LauncherTheme` 语义资源、`launcher_*` 颜色；不在 XML 硬编码 `#RRGGBB` 或固定圆角 drawable。
- 使用 ViewBinding 而非 `findViewById`；Fragment 在 `onDestroyView()` 中解除 listener 并置空 binding。
- 新增普通页面通过 `LauncherActivity.wrapLauncherUiMode()` 包装 Context，并在 `super.onCreate()` 前应用保存的模式。

## 3. 主题色调与字体

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
- 粒子总开关与样式只通过 `LauncherActivity` 的 `launcher_particles_enabled`、`launcher_particle_style` 读写；样式限定为 `floating`、`rain`、`star`、`sakura`、`fireflies`、`constellation`、`ripples`。设置页变更后调用所在页面的 `renderParticles()`，不要临时复制一套粒子状态。
- `LauncherParticleView` 在绘制时检查 `LauncherActivity.getLauncherThemeStyle()`；主题风格变化后会按粒子原有色位重新着色，不需要重建页面或重新创建动画线程。
- 默认主题保持现有的柔和彩色粒子调色板；凛弥、杏璃、心海天主题以各自 primary color 为基色，生成同色相、低饱和/分层亮度的粒子变体。粒子不得使用与当前主题无关的固定高饱和色。
- 新增主题风格时，必须同步扩展 `LauncherParticleView.particleColor()` 的取色分支，并以该主题的 primary tone 为基色生成变体；只改按钮主色、不改粒子取色视为主题未完成。
- 心海天按钮可使用主/强调色渐变，但粒子以其 primary color 为基色做亮度层次，避免背景出现大面积高对比粉色而抢占游戏卡片和文字可读性。
- 颜色变化只能更新已有粒子颜色；保留既有 56 个粒子、16ms 帧节奏、可见性/附着状态停止渲染的生命周期约束，禁止在主题切换时叠加新的 Runnable 或动画实例。

### 粒子样式清单

共 7 种粒子样式，均通过 `LauncherActivity.PARTICLE_STYLE_*` 常量标识，在 `LauncherParticleView` 内以 `isXxxStyle()` 分支选择更新与绘制逻辑：

| 样式 | 常量 | 说明 |
|---|---|---|
| 漂浮光点 | `floating` | 默认样式，向上漂移的彩色圆点 |
| 斜向雨滴 | `rain` | 斜向短线段，模拟雨滴下落 |
| 星星粒子 | `star` | 十字短线，脉动透明度不位移 |
| 按键瀑布 | `sakura` | 方块/三角/圆/十字四种游戏按键图形向下流动，左右边缘回弹；仅前 `SAKURA_ACTIVE_COUNT`(20) 个粒子为活跃源，大小固定 |
| 萤火虫 | `fireflies` | 径向渐变光晕，随机游走并脉动明暗 |
| 星座连线 | `constellation` | 漂移的圆点，邻近粒子之间绘制连线 |
| 涟漪扩散 | `ripples` | 同心圆环扩散，进度满后随机重生；仅前 `RIPPLES_ACTIVE_COUNT`(8) 个粒子为活跃涟漪源 |

- 每种样式在 `createParticle(index, width, height)` 中按需初始化专属字段；`createParticle` 接收 index 参数以支持 ripples 等依赖位置索引限制活跃数量的样式。
- 每个绘制分支必须显式设置 `paint` 的 `style`、`strokeWidth`、`shader`（用完置 null），复用同一 `paint` 实例，不新建 Paint。
- 萤火虫样式的 `RadialGradient` shader 按 `colorIndex` 缓存到 `fireflyShaders[]`，主题切换时由 `syncThemeColors()` 清空缓存重建，避免持有旧主题颜色。
- 涟漪样式通过 `maxRadius <= 0` 标记休眠粒子（index >= `RIPPLES_ACTIVE_COUNT`），`updateRipples` 与 `drawRipples` 需在开头跳过休眠粒子。
- 按键瀑布样式通过 `radius <= 0` 标记休眠粒子（index >= `SAKURA_ACTIVE_COUNT`），`updateSakura` 与 `drawSakura` 需在开头跳过休眠粒子；活跃粒子大小固定不随机。
- 新增粒子样式时，必须同步扩展 `LauncherActivity.setLauncherParticleStyle()`/`getLauncherParticleStyle()` 的 `safeStyle` 校验、`LauncherParticleView.setParticleStyle()` 与 `isXxxStyle()` 分支，以及 `PadSettingsActivity` 与 `LauncherThemeMenuActivity` 的样式选择弹窗。

## 4. 竖屏 Launcher 组件

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

### 开关

- 所有 `SwitchCompat` 必须调用 `LauncherTheme.styleMaterialSwitch()`；禁止在新页面或修改到的存量页面继续使用 `styleSwitch()`。
- 统一规格为 49×29dp 轨道、21dp 圆点：开启态为运行时主题主色轨道与白色圆点；关闭态为 muted gray 描边轨道与完整灰色圆点。
- 开关不显示按下波纹或背景高亮；保留可点击性、无障碍状态与既有存取逻辑。不得在 XML 或业务页面直接覆盖其 drawable、tint、尺寸或颜色。

## 5. 竖屏弹窗

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
- Activity 内不得手写对话框 View 树（手拼 `LinearLayout`/`TextView`/`Button`）；权限引导、确认等弹窗一律走 `LauncherDialogFactory` 的 `show*()` API，复用 `root()`/`standardTitle()`/`standardMessage()`/`button()` 等 helper 保持外壳统一，避免在 Activity 中堆积 70+ 行内联对话框代码。新增弹窗类型优先扩展 Factory，不要回退到 Activity 内联实现。
- 含输入法、动态进度、复杂列表或不可控长文本的弹窗不能硬迁移到普通 API；先提供有明确生命周期的专用模板，再迁移。

## 6. PadUi 横屏规范

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
- 游戏详情：保留 288dp 详情容器；长 URI/包名通过 `TextView` 的 `maxLines` + `ScrollingMovementMethod` 实现内部滚动，内容短时正常显示、超长可垂直滚动查看。

### 弹窗实现纪律

- `PadManageFragment` 的 `createLauncherDialog`/`createDialogRoot`/`createDialogTitle`/`createDialogButton`/`createDialogCancelButton` 等 helper 仅服务于上述 4 个保留专用实现；新增同类弹窗必须直接用 `PadDialogFactory` 的对应 `show*()` API，不要扩展这些 helper 或手写 root/title/button。
- 专用实现的弹窗宽度必须通过 `PadDialogFactory.dialogWidthPx(context, widthDp)` 做屏幕宽度兜底（`min(densityWidth, screen-48dp)`），不要直接传 `dp(288)`/`dp(270)`，避免极窄屏溢出。
- `showConfirm`（双按钮确认，288dp）使用 inflate 的 `dialog_launcher_confirm` 布局实现水平并排按钮；`showStandardConfirm`（普通确认，270dp）使用程序化构建的垂直按钮。两者是不同弹窗类型，水平/垂直差异是有意设计，不是实现不一致；不要为统一而合并。
- 同步确认、账户确认归类为"普通确认"，使用 `showStandardConfirm`（270dp）；只有需要水平双按钮的启动确认才用 `showConfirm`（288dp）。
- 粒子样式选择使用 `PadDialogFactory.showSingleChoice`（可滑动单选列表），通过 `checkedIndex` 表达已选状态、末项"关闭动态粒子"表达关闭操作、回调内 `Toast` 反馈；不要再手写选项行。
- `PadManageFragment` 跨包调用 `com.apps.settings.LauncherCustomVndbSearchDialog` 属于复杂搜索专用界面，超出 PadUi 弹窗规范范围，保留跨包调用。

## 7. 修改与验证清单

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

## 8. `app/src/main/java` 通用代码规范

本章适用于 `com.apps.*` 与 `com.core.*`。第 1～7 章仍是 UI 改动的优先规则；如本章与既有实现冲突，新代码遵循本章，存量代码只在相关功能改动时渐进迁移，禁止为满足格式要求进行无业务价值的大规模重写。

### 分层与包依赖

- `com.apps.*` 是展示层：Activity、Fragment、Adapter、View、UI 状态及导航协调代码。它可以依赖 `com.core.*`，但不直接承担持久化、网络协议解析或可独立复用的领域决策。
- `com.core.*` 是业务与平台层：model、data、net、sync、scanner、importer、launcher、metadata、translation 的非 UI 能力及通用 util。`com.core.*` 禁止 import `com.apps.*`，也不得直接持有或启动 Activity、Fragment、Dialog、Theme 等 UI 对象。
- UI 所需的 core 回调通过接口、状态模型或事件向上交付；core 不反向调用 UI。Application 级 UI 初始化应移到 `com.apps` 的启动协调代码，避免 core 成为 UI 的依赖方。
- 新包名一律小写，使用 `lowercase`；新增 Pad 代码放入 `com.apps.padui`，用户数据代码放入 `com.apps.userdata`。既有 `PadUi`、`UserData` 仅在其周边改动时迁移，迁移时同步更新所有引用。
- 按功能归属文件：一个 feature 的 UI、状态和协调代码放在对应 `com.apps.<feature>`；可复用的领域能力放在对应 `com.core.<feature>`。不要仅为“工具类”而创建无业务语义的公共包。

### 状态、线程与生命周期

- 新增 Kotlin 异步代码使用结构化协程：页面相关任务使用 `lifecycleScope`，ViewModel 任务使用 `viewModelScope`，并显式指定 `Dispatchers.IO`、`Default` 或 `Main`。不得使用 `GlobalScope`。
- `AppExecutors` 是 Java 及存量调用的兼容层；新增 Kotlin 业务代码不新增 `AppExecutors + runOnUiThread` 链路。迁移旧代码时优先按所在生命周期替换，而不是在无生命周期的静态对象中创建 scope。
- 后台任务不得直接更新 View；回到主线程前先确认 Activity/Fragment 仍有效。Fragment 的 View 任务必须在 `onDestroyView()` 后自动取消或不再访问 binding。
- Repository/Bridge 返回领域结果或 `Result`；UI 层只负责加载、成功、空态和失败态渲染。不要让 Activity/Fragment 同时处理 HTTP、文件读写、持久化和复杂业务决策。
- 共享状态仅在确有并发访问时使用 `@Volatile`、锁或原子类型；说明其保护的状态与线程边界，避免以全局可变单例代替状态所有权。

### 异常、日志与数据处理

- 禁止捕获 `Throwable`，除非处于明确的进程边界、回滚清理或日志兜底点，且必须说明原因；不得吞掉 `CancellationException`、`InterruptedException`、`Error` 等不可恢复信号。
- 只捕获可预期的具体异常；失败必须返回给调用方、显示合适的用户提示或写入 `DevLogger`。允许忽略的异常必须在紧邻 catch 处说明为何可安全忽略。
- 不使用 `!!` 作为常规控制流。可空值优先使用 `?.`、`?:`、提前返回或 `requireNotNull`（仅用于违反内部不变量的情形）。
- 时间展示统一复用 `TimeFormatUtil`；网络协议或导入格式解析可使用专用 `SimpleDateFormat`，但须显式指定 `Locale` 与格式来源。不要在 UI 中重复实现通用展示格式。
- 用户可见文本优先放入资源；日志不得包含 access token、API key、密码或完整的私有路径。涉及外部输入、文件与 URI 时先校验可读性、边界和编码。

### 文件职责与可维护性

- 一个类/文件只保留一个主要职责。页面拆分为 UI 绑定与渲染、状态/事件协调、业务操作；Repository 拆分为查询、写入、迁移或外部源适配等明确职责。
- 单文件超过约 500 行、或同时包含 UI、线程调度、I/O 和领域规则时，应在下一次相关功能改动中拆分。优先提取可独立测试的 Controller、Use Case、Formatter 或数据源，不改变对外行为。
- **Activity 的 companion 不得承担全局静态职责**：偏好读写、主题判断、UI 模式包装、Splash 资源、导航样式等与 Activity 实例无关的静态能力，必须放在独立 `object`（参考 `LauncherPreferences`、`LauncherThemeStyle`、`LauncherUiMode`、`LauncherSplash`、`LauncherNavRenderer`）。`LauncherActivity` 的 companion 只作为兼容委托层保留既有 `@JvmStatic` 签名，不得新增实现；新增调用方直接引用对应 object，不经 companion 转发。Activity 实例逻辑（生命周期、路由、装配）与渲染/状态管理应分离，导航渲染等大块 UI 逻辑抽成持有 Activity 的协调类，避免单 Activity 文件膨胀。
- **object 间禁止循环依赖**：低层工具 object（`LauncherNavigationMetrics`、`LauncherPreferences` 等）不得反向依赖高层 `LauncherActivity`。SharedPreferences 名等共享常量统一以 `LauncherPreferences.APP_PREFS` 为单一来源，不通过 `LauncherActivity.APP_PREFS` 回跳，避免形成模块级循环引用。新 object 只依赖同层或更底层的 object/`Context`。
- **常量委托的约束**：`const val` 无法委托到另一 object 的属性，大规模重构时可在兼容层保留字面量副本并注明主源；`@JvmField val` 可用 `@JvmField val X = LauncherY.X` 形式委托以保持单一来源。重构时优先保留 `@JvmStatic` 委托方法签名与 `@JvmField`/`const val` 常量名不变，让既有调用方零修改、分步迁移，降低回归风险。
- **持有 Activity 的渲染/协调类**：导航渲染、Splash 等 UI 协调类需要访问 Activity 状态时可持有 Activity 引用，但须满足：生命周期严格被 Activity 包裹（在 `onCreate`/`showLauncherContent` 等创建，Activity 销毁即释放）；对 Activity 私有字段的访问通过 `internal` getter 暴露（如 `internal val launcherBinding`），不直接改为 `public`；`lateinit` 字段必须在访问前用 `binding != null` 等守卫保证已初始化，避免在 splash 等异步阶段触发未初始化访问。
- 新增公共 API 需有 KDoc/Javadoc：说明用途、线程要求、可空约定、失败方式及 Java 互操作约束（若适用）。不要为显而易见的私有实现添加噪声注释。
- 保持 import 分组和格式化一致；不引入与文件现有语言无关的 Java/Kotlin 互操作包装。新业务类优先 Kotlin，Java 文件仅维护既有实现或确有互操作必要的代码。

### 自动检查与提交要求

- 新增或迁移代码应通过 Android Lint、Kotlin 格式检查和静态分析；在引入工具前，至少执行构建与 `git diff --check`。建议逐步接入 Ktlint/Spotless 与 Detekt，并先对新增问题设为阻断。
- 涉及分层调整时，检查 `com.core` 不新增 `import com.apps`；涉及线程调整时，验证页面销毁后没有 UI 更新或任务泄漏。
- 最低验证命令保持如下；涉及 core 逻辑时同时补充或运行对应单元测试：

```bash
./gradlew :app:assembleDebug
git diff --check
```
