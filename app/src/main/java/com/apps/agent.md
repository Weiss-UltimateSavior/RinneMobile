# `app/src/main/java` 代码协作规范

本文件是 `app/src/main/java` 后续改动的执行基线：第 1～7 章为 `com.apps.*` 界面层规则（UI 改动优先适用），第 8 章为 `com.apps.*` 与 `com.core.*` 通用代码规范。先遵循现有页面的业务路径与资源命名；仅在同类组件已经重复出现时抽公共层，不为一次性界面建立平行体系。

（历史说明：早期版本曾声明页面/布局/命名约定由 `Launcher_UI_Specification.md` 负责、本文件仅补充组件与弹窗规则；该文档已不存在，页面与布局规范并入本文件 §4/§6 及既有 UI 资源体系，此声明不再适用。）

## 1. 通用原则

- 保持 `LauncherActivity.wrapLauncherUiMode()`、`LauncherTheme` 与 `LauncherMotion` 的既有主题/动效链路。
- 主题/色调/偏好等全局静态 API 的主源已落在独立 `object`（`LauncherPreferences`、`LauncherThemeStyle`、`LauncherUiMode`、`LauncherSplash`、`LauncherNavigationMetrics`）；`LauncherActivity` 的 companion 仅保留 `@JvmStatic` 委托方法与少量 `const val` 兼容常量。Phase 2 已清零 `@JvmField val` 兼容常量；仍保留的 `const val` 仅限 Intent extra/action、偏好键、Splash 时长三类，作为独立技术债阶段清理（具体清单见重构计划文档），不在功能改动中混提。新增静态 API 直接写入对应 object，不再向 `LauncherActivity` companion 添加实现或常量。
- **常量单一来源**：SharedPreferences 名（`APP_PREFS`/`ACCOUNT_SETTINGS_PREFS`/`PROFILE_PREFS`）、偏好键（`KEY_LAUNCHER_DARK_MODE`/`KEY_LAUNCHER_PARTICLES_ENABLED`/`KEY_LAUNCHER_PARTICLE_STYLE`/`KEY_LAUNCHER_THEME_STYLE`）、粒子样式（`PARTICLE_STYLE_*`）、主题风格（`THEME_STYLE_*`）、主题主色（`*_PRIMARY_COLOR`，`@JvmField`）均以 `LauncherPreferences`/`LauncherThemeStyle` 为唯一来源；禁止在 Activity/Fragment 内定义同名局部常量或硬编码字面量。`com.core` 模块如需引用这些名称，通过 core 侧镜像常量或下沉到 `com.core` 公共常量 object，不在 core 侧硬编码字面量。
- **工具方法单一来源**：dp 转换统一用 `LauncherTheme.dp(context, value)`（Int/Float 重载），禁止在 Activity/Fragment/Adapter/Dialog 内保留本地 `dp()` 副本；需额外缩放因子（如平板竖屏缩放）时包装 `LauncherTheme.dp()` 并保持单次舍入语义（`Math.round(value * density * scale)`，不两次舍入）。沉浸式窗口配置统一用 `LauncherEdgeToEdgeHelper.apply(activity)` 或 `apply(activity, adjustResize=true)`，禁止内联 `configureEdgeToEdgeWindow()` 实现；luminance 模式（chat 类页面按主色亮度决定 LIGHT_STATUS_BAR）需扩展 helper 重载或保留独立实现并注释差异。外部 URL 打开统一用 `LauncherUrlOpener.open(context, url)`，禁止裸 `startActivity(Intent.ACTION_VIEW)` 不带 catch。**系统栏 insets 应用**（捕获目标 View 原始 padding + systemBars inset 累加）统一用 `com.apps.common.LauncherInsetsHelper`（`applyTopInset`/`applyTopAndBottomInsets`/`applyInsets`/3 参 bottomPadding 变体），新页面禁止自实现 `setOnApplyWindowInsetsListener` + `systemWindowInset*` 累加组合；已登记的多 target/复杂形态存量（`LauncherProfileFragment`/`AvatarCropActivity`/`LauncherLibraryFragment`/`LauncherRegisterFragment`，见重构计划 4.5）渐进迁移。**聊天消息布局 insets/overlay padding**（顶部 overlay/标题栏/输入条 + IME）统一用 `com.apps.chat.ChatInsetsHelper`（`install(root, layout, baseBottomPadding)`，返回重排回调）。**弹窗上下文路由**：新业务 Fragment 弹窗统一经 `com.apps.HDModel.LauncherDialogRouter`（沿 ContextWrapper 判断 HdModeActivity 壳，HD/Pad 路由 `PadDialogFactory`、竖屏委托 `LauncherDialogFactory`），新 Fragment 禁止直接调用 `LauncherDialogFactory`。
- **统一工具入口（com.apps）**：图片 URI 加载统一用 `com.core.util.SafeImageLoader`（`loadUri`/`invalidateUri`/`clearMemoryCache`，IO 线程解码 + 12MB LruCache + 32MB 源字节上限）；游戏封面专用 `com.apps.widget.LauncherCoverLoader`（`loadInto`/`clear`/`clearMemoryCache`，480×432 降采样上限），两者分工：封面走 CoverLoader、背景/头像走 SafeImageLoader。页面/弹窗动效统一用 `com.apps.theme.LauncherMotion`（`applyActivityOpen`/`applyActivityClose`/`finish`/`applyDialogMotion`/`pulse`/`recreateWithToneOverlay`/`runAfterPulse`），禁止裸 `overridePendingTransition`。竖屏平板布局缩放统一用 `com.apps.widget.LauncherTabletPortraitScaler`（`apply`/`scaleFor`/`dp`/`libraryGridColumns`/`libraryPageSize`），禁止自行乘密度系数。游玩状态/引擎名/标题等纯格式化统一用 `com.apps.game.GameMetadataFormatter`（`safeTitle`/`playStatusText`/`engineText`/`normalizePlayStatus`/`parseDevelopers`）。"internal." 引擎前缀拼接统一用 `com.apps.game.EnginePackageResolver.internalPackage`；引擎选择器选项统一用 `com.apps.game.EngineOptionCatalog.create`。游戏分类构建统一用 `com.apps.game.GameCategoryBuilder`；游戏密码锁定统一用 `com.apps.game.GamePasswordLock`。底部导航内容避让统一用 `LauncherNavigationMetrics.overlayBottomPadding`/`navigationOverlayBottomPadding(fallback)`。更新弹窗文案统一用 `com.apps.theme.LauncherUpdateFormatter`（`buildUpdateMessage`/`resolveUpdateUrl`）。桌面快捷方式统一用 `com.apps.game.PinnedGameShortcut`（`requestPinShortcut`/`launchPinnedGame`/`clearIconCache`）。Launcher 启动 Intent action/extra 键统一用 `com.apps.LauncherIntents`。头像文件持久化统一用 `com.apps.util.LauncherAvatarPersistence`（键主源 `com.core.CorePreferences.KEY_PROFILE_AVATAR`）。**内存缓存清空三件套**：`SafeImageLoader.clearMemoryCache()`/`LauncherCoverLoader.clearMemoryCache()`/`PinnedGameShortcut.clearIconCache()` 由 `LauncherActivity.onTrimMemory` 统一调用，新增图片缓存必须在三件套登记清空入口。
- 新增普通 Activity/Fragment 使用 ViewBinding；Fragment 在 `onDestroyView()` 解除 listener 并置空 binding。
- 不以静态颜色或静态圆角 drawable 覆盖运行时主题。优先调用 `LauncherTheme`。
- 修改 UI 时只调整用户指定的层级，不顺带改动引擎启动、存档、账户同步或列表数据流。
- 竖屏与 Pad 横屏分别复用各自组件；禁止把竖屏平板缩放器直接套到 `PadUi`。
- **重复实现优先删除而非保留**：当两个类/方法实现 95% 以上相同逻辑时（如 `LauncherGameActionController.java` 与 `GameActionMenuFactory.kt`），必须让唯一调用方迁移到保留实现后整体删除重复类，不在两个实现上并行修补。保留重复类会同时累积双份的 catch(Throwable)、`runOnUiThread` 未守卫与代码膨胀，违反单一来源。删除前必须确认：调用方已切换、`@JvmStatic`/`@JvmField` 兼容签名无外部引用、相关测试或实机路径覆盖。

## 2. 新增功能代码规范

### 语言与文件选择

- 新增 Activity / Fragment / Dialog 工具类 / Adapter / ViewHolder / Controller / Manager / Repository / 工具类一律使用 Kotlin；不再新建 Java 页面或 UI 工具文件。近期违规新建的 Java 文件清单见重构计划文档，在相关功能改动时迁 Kotlin。
- 已存在的 Java 文件可在原文件内继续修改；只有大规模重构时才迁移到 Kotlin。
- 新增 Kotlin 业务文件放在与职责对应的 `com.core.*` 子包下；UI 页面放在 `com.apps.*` 对应子包。

### 页面三语与本地化

- 页面中所有用户可见文案必须资源化：XML 使用 `@string/...`，Kotlin/Java 使用当前页面或传入 `Context` 的 `getString()`；禁止在 Activity、Fragment、Adapter、Dialog、Toast、加载页或引擎壳层硬编码中文、英文或日文。
- 每个新增或修改的用户可见字符串，必须同时维护 `res/values/strings.xml`、`res/values-en/strings.xml` 与 `res/values-ja/strings.xml` 中的同名 key。简中、英文、日文缺任一项，视为页面未完成。
- 页面展示的动态文字保存语义原值（状态、类型、资源 key、枚举或原始数据），在绑定/渲染时按当前 `Context` 解析为三语文案；不得在 Repository、ViewModel、单例或 Adapter 数据中缓存已经翻译的字符串，以保证切换语言后立即刷新。
- 跨进程或内置引擎页面必须通过 `Intent` 明确传递当前语言 tag，并在目标进程基于该 tag 创建本地化 Context 后读取资源；不得依赖旧页面、Application Context 或进程启动时缓存的 Locale。
- 数据库字段、Intent extra key、偏好设置 key、网络协议、游戏目录标记和引擎类型等机器可读值保持稳定原文，不得翻译；只在 UI 层为其提供本地化展示名称。
- 语言切换完成后，当前可见页面、列表项、在线状态、加载遮罩和弹窗都应重新读取资源并刷新；验证时至少检查简中、英文、日文三种语言，以及页面旋转或重建后的显示。

### Kotlin 代码风格

- 工具类 / 桥接类使用 `object` + `@JvmStatic` 暴露入口给 Java 调用方；常量使用 `const val`；包内可见方法使用 `internal`。`internal` 函数若被同模块 Java 代码调用，须加 `@JvmName("原名")` 避免 name mangling；纯 Kotlin-to-Kotlin 的 internal 无需加。
- 跨 Java 边界的方法参数优先声明为可空（如 `String?`），兼容 Java 调用方传入 null；保留既有约定（参考 `VndbClient.kt`、`MetadataUtils.cleanTitle()`）。
- 需要复刻 Java `trim()` 语义时使用 `uriText == null || uriText.trim().isEmpty()`，不用 `isNullOrBlank()`（参考 `SafeImageLoader.kt`）。
- 需要匹配 Java `Math.round(float)` 半向上语义时使用 `Math.round(float)`，不用 `roundToInt()`（参考 `UiScaleUtil.kt`）。
- 集合优先使用只读 `List` / `Map`，需要可变时显式使用 `MutableList` / `MutableMap`。
- 不滥用 `!!`；可空值用 `?.` 或 `?:` 兜底。
- 单方法接口使用 `fun interface`；Java 枚举迁移使用 `enum class` + `companion object` + `@JvmStatic fromString`。存量 Java 单方法接口在所在文件因业务改动被打开时一并迁 `fun interface`，不专项发起大规模迁移（候选清单见重构计划文档）。
- `@Volatile` 仅用于跨线程可见性需求；只在 `@Synchronized` 方法内访问的字段不需要 `@Volatile`。
- **JavaBean 访问器 API 的类迁 Kotlin 时保留显式 Java 风格函数**：状态 getter/setter 用显式函数（`isLoading()`/`setDataLoaded()`/`getVisibleGames()`）而非 Kotlin 属性（阶段 60 实证：Kotlin 属性语法下 `obj.setDataLoaded(true)`/`obj.getVisibleGames()` 编译失败，报 Unresolved reference）。原 Kotlin 调用方的「属性读」语法（如 `obj.isDataLoaded`）须机械改为方法调用（`obj.isDataLoaded()`）。

### ViewModel 与 Fragment 通信

- ViewModel 创建统一用 `ViewModelProvider.Factory` 或 `by viewModels()`/`by activityViewModels()` Kotlin 扩展；`AndroidViewModel` 默认构造可例外，但加构造参数时必须显式 Factory，不依赖反射构造。禁止 `ViewModelProvider(this).get(X::class.java)` 裸构造带参 ViewModel。
- Fragment ↔ 子 Fragment/BottomSheet 通信优先用 Fragment Result API（`setFragmentResult`/`setFragmentResultListener`），监听方绑定 `viewLifecycleOwner`；跨页面共享状态用 SharedViewModel（`by activityViewModels()`）。禁止通过 Activity 强转、public 字段直传或单例 EventBus。
- ViewModel 对外暴露 `LiveData`/`StateFlow` 不可变视图，内部可变状态用 `MutableLiveData`/`MutableStateFlow` 私有持有；UI 层只观察不写入。

### XML 布局规范

- 新增布局文件命名沿用 `snake_case`：`activity_xxx`、`fragment_xxx`、`dialog_xxx`、`item_xxx`、`view_xxx`。
- 优先复用现有 style、`LauncherTheme` 语义资源、`launcher_*` 颜色；不在 XML 硬编码 `#RRGGBB` 或固定圆角 drawable。
- 使用 ViewBinding 而非 `findViewById`；Fragment 在 `onDestroyView()` 中解除 listener 并置空 binding。
- 新增普通页面通过 `LauncherActivity.wrapLauncherUiMode()` 包装 Context，并在 `super.onCreate()` 前应用保存的模式。

## 3. 主题色调与字体

### 主题链路

- 深/浅模式由 `LauncherActivity.applySavedToneMode()` 和 `wrapLauncherUiMode()` 决定；新 Activity 必须在 `super.onCreate()` 前应用保存的模式，并在 `attachBaseContext()` 包装 Context。
- 页面根、普通内容卡片、文本与分割线只引用 `launcher_*` 语义资源。颜色值由 `values/colors.xml` 与 `values-night/colors.xml` 分别提供，禁止在 `com.apps` 页面硬编码 `#RRGGBB`。
- 用户主题风格只改变 **primary tone**：`default`、`rinne`、`anri`、`xinhaitian`、`natsume`、`izumi`（共 6 项，由 `LauncherThemeStyle.THEME_STYLE_*` 单一定义）。必须通过 `LauncherTheme.primary()` / `LauncherActivity.launcherPrimaryColor()` 读取，不能直接引用默认绿 `launcher_primary_color` 作为运行时颜色。
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
- **取色统一**：禁止在 `com.apps` 页面硬编码 `Color.GRAY`/`Color.WHITE`/`Color.BLACK`/`Color.parseColor("#...")` 作为运行时颜色。次级文字/占位灰统一用 `LauncherTheme.textMuted(context)`；深色模式白色 tint 用 `LauncherTheme.applyCardCircleIcon()` 等已封装方法；背景用 `LauncherTheme.bg(context)`；分割线用 `LauncherTheme.line(context)`。需要纯白/纯黑做遮罩或混合基色时（如 `ColorUtils.blend`）可使用 `Color.WHITE`/`Color.BLACK`，但必须紧邻注释说明是混合基色而非页面取色。`ContextCompat.getColor(context, R.color.launcher_*)` 直接取色仅在 `LauncherTheme` 内部使用，页面层应调用 `LauncherTheme.*()` 语义方法。
- **导航图标取色**：导航中心/选中态图标 tint 统一封装，不在各横屏 Activity/Renderer 内重复 `setColorFilter(Color.WHITE)`/`Color.GRAY` 分支。选中态用 `LauncherTheme.primary(context)`，未选中态用 `LauncherTheme.textMuted(context)`；白色 tint 走 `LauncherTheme` 封装方法。多处 nav 染色逻辑重复，应合并到 `LauncherNavRenderer`（具体位置见重构计划文档）。

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

- 首页和 Pad 横屏背景统一使用 `LauncherParticleView`；它是装饰性背景层，必须置于内容之后、不可获取焦点、不可拦截触摸，也不能放进引擎、存档或账户等业务 Activity。构造时必须显式调用 `setFocusable(false)` / `setClickable(false)` / `setFocusableInTouchMode(false)`，不依赖默认值；任何新增的纯装饰 View（背景粒子、水印、装饰渐变层）同理。
- 粒子总开关与样式只通过 `LauncherActivity` 的 `launcher_particles_enabled`、`launcher_particle_style` 读写；样式限定为 `floating`、`rain`、`star`、`sakura`、`fireflies`、`constellation`、`ripples`。设置页变更后调用所在页面的 `renderParticles()`，不要临时复制一套粒子状态。
- `LauncherParticleView` 在绘制时检查 `LauncherActivity.getLauncherThemeStyle()`；主题风格变化后会按粒子原有色位重新着色，不需要重建页面或重新创建动画线程。
- 默认主题保持现有的柔和彩色粒子调色板；凛弥、杏璃、心海天主题以各自 primary color 为基色，生成同色相、低饱和/分层亮度的粒子变体。粒子不得使用与当前主题无关的固定高饱和色。
- 新增主题风格时，必须同步扩展 `LauncherParticleView.particleColor()` 的取色分支，并以该主题的 primary tone 为基色生成变体；只改按钮主色、不改粒子取色视为主题未完成。
- 心海天按钮可使用主/强调色渐变，但粒子以其 primary color 为基色做亮度层次，避免背景出现大面积高对比粉色而抢占游戏卡片和文字可读性。
- 颜色变化只能更新已有粒子颜色；保留既有 56 个粒子、16ms 帧节奏、可见性/附着状态停止渲染的生命周期约束，禁止在主题切换时叠加新的 Runnable 或动画实例。

### 粒子样式清单

共 7 种粒子样式，均通过 `LauncherPreferences.PARTICLE_STYLE_*` 常量标识，在 `LauncherParticleView` 内以 `isXxxStyle()` 分支选择更新与绘制逻辑：

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

### 新增主题风格：完整清单与路径

新增主题风格（如「和泉妃爱/izumi」）时，以下改动缺一即视为主题未完成。以 `izumi` 为主题风格存储值为例，`rinne/anri/xinhaitian/natsume/izumi` 五个存量主题均已按本清单落地，可作对照蓝本。

1. **主题源 object** —— `com.apps.LauncherThemeStyle`（偏好/主色唯一来源，禁止在 Activity/Fragment 定义同名局部常量）：
   - 常量 `const val THEME_STYLE_IZUMI = "izumi"`（持久化存储值，发布后不得改动）
   - 主色 `@JvmField val IZUMI_PRIMARY_COLOR = Color.rgb(…)`（主题色调，如 `#d6a826` → `Color.rgb(214, 168, 38)`）
   - `setThemeStyle` 的白名单 `when` 加入该风格
   - 新增 `isIzumi()`，并在 `primaryColor()`、`homeStatsImageRes()` 各加对应分支
2. **图片素材** —— 复制到 `app/src/main/res/drawable-nodpi/`，命名规范：
   - 主题菜单 logo → `launcher_theme_izumi_logo.png`
   - 导航栏/悬浮翻译主题 logo → `launcher_theme_izumi_def.png`
   - 卡片容器/首页资料统计背景 → `launcher_home_stats_izumi_bg.png`
3. **导航 logo 接入**：
   - `com.apps.LauncherApplication.themeLogoRes` 加 `isIzumi -> launcher_theme_izumi_def`
   - `com.apps.LauncherNavRenderer` 三处：`navPillLaunchCenterIcon`、`liquidGlassLandscapeIcon` 的资源分支，`applyThemeLogoTone()` 的 `themedIcon` 与 logo 分支，以及 `applyCenterLogoScale()` 参数与缩放分支
4. **首页资料统计**：`com.apps.home.LauncherHomeFragment` 的 `isDefault` 表达式追加 `&& !LauncherActivity.isIzumiTheme(requireContext())`
5. **Activity 兼容委托**：`com.apps.LauncherActivity` companion 增加 `@JvmStatic isIzumiTheme(context)`（仅委托 `LauncherThemeStyle.isIzumi`，不加实现）
6. **粒子取色**：`com.apps.theme.LauncherParticleView.particleColor()` 增加 izumi 分支，以 `IZUMI_PRIMARY_COLOR` 为基色生成变体（默认主题除外）
7. **竖屏主题菜单 UI**：`com.apps.theme.LauncherThemeMenuFragment`（行点击、logo 圆底、选中态、`applySelectedTheme` 分支）+ `res/layout/activity_launcher_theme_menu.xml` 新增行（logo/名称/简介/勾选 ✓）。HD 设置复用该 fragment，自动生效，无需另改。
8. **Pad 设置 UI**：`com.apps.PadUi.PadSettingsActivity`（`THEME_IZUMI_LABEL`、`bindActions`、`restoreSelectedTheme`、`applyThemeMenuTone`、`renderThemeSelection`、`applySelectedTheme`）+ `res/layout/activity_pad_settings.xml` 新增行
9. **三语文案**（zh/en/ja 缺一视为未完成）：
   - `res/values*/strings_settings.xml`：`theme_izumi_display_name`、`theme_izumi_description`、`theme_izumi_applied`
   - `res/values*/strings_pad.xml`：`pad_theme_izumi_applied`、`pad_theme_izumi_content_description`
10. **验证**：`./gradlew :app:compileDebugJavaWithJavac :app:compileDebugKotlin`（或 `:app:assembleDebug`）+ `git diff --check`

> 原则同 §3「动态粒子与主题色」：新增主题只改变 primary tone；粒子必须同步取该主题 primary 为基色。禁止只改按钮主色、菜单图标而不改粒子与资料统计背景。

## 4. 竖屏 Launcher 组件

### 首页风格切换

- 首页风格的唯一类型定义是 `com.apps.home.HomeStyle`。新增风格必须增加枚举项，并为其声明稳定的 `storageValue` 与 `labelResId`；禁止重新使用 Boolean、数组下标、枚举 `ordinal` 或可本地化文案作为持久化值。
- `storageValue` 属于持久化协议，发布后不得随类名、产品文案或枚举名称调整。未知或已下线的存储值必须通过 `HomeStyle.fromStorage()` 安全回退到 `DEFAULT`，不得导致启动崩溃。
- 首页风格统一由 `LauncherPreferences.getHomeStyle()` / `setHomeStyle()` 读写。偏好使用字符串键 `launcher_home_style`；历史键迁移在 `LauncherPreferences` 内集中完成，UI、Fragment 和 Activity 不得自行读取、写入或迁移首页风格偏好。
- 旧偏好迁移必须保留用户原选择，并在写入新值后删除旧键。新增后续迁移时继续采用“读取旧值 → 转换为 `HomeStyle` → 写入稳定字符串 → 删除旧键”的单向迁移，不保留两套状态并行写入。
- `LauncherHomeFragmentFactory` 是 `HomeStyle` 到具体首页 Fragment 的唯一映射点，同时负责 Fragment 创建与当前类型匹配。`LauncherActivity`、设置页和导航代码不得出现 `if (featured)`、`when (homeStyle)` 或直接构造具体首页 Fragment 的平行映射。
- `LauncherActivity.showFragment()` 在一次渲染中只读取一次 `HomeStyle`，并将同一值同时用于 `matches()` 和 `create()`，避免偏好在判断与创建之间发生不一致。首页类型匹配使用精确 `javaClass`，不能使用 `is LauncherHomeFragment`，因为风格 Fragment 可以继承默认首页。
- 普通设置页使用 `HomeStyle.entries`，Java/Pad 存量页面使用 `HomeStyle.values()` 动态生成选项和选中索引；显示文案统一读取枚举的 `labelResId`。禁止硬编码两项数组、`index == 1` 或基于具体风格名称推导选择状态。
- `HomeStyle` 当前只控制竖屏 Launcher 首页。`HdHomeFragment` 有独立的横屏布局规则；除非需求明确要求 HD 同步切换，否则不得仅因共享偏好而替换 HD 首页。
- 风格差异仅限局部排列、item 布局、图标色等表现时，优先继承 `LauncherHomeFragment` 并覆盖现有扩展点：`recentItemLayoutRes()`、`recentDisplayLimit()`、`recentGridColumns()`、`bindRecentItem()`、`onHomeLayoutReady()`、`applyIconTone()`。当页面结构和交互已明显分叉时，应提取共享业务基类并使用独立 XML，不要持续在运行时拆装默认布局。
- 新增首页风格必须同时完成：三语字符串资源、`HomeStyle` 枚举项、具体 Fragment、`LauncherHomeFragmentFactory` 映射、普通设置页和 Pad 设置页选项验证，以及冷启动、设置页返回即时切换、进程重建和未知存储值回退验证。

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
- 自定义 `EditText` 子类必须显式传 `defStyleAttr = android.R.attr.editTextStyle`，否则 Java→Kotlin 迁移后可能丢失可编辑行为（参考 `LauncherEditText`）。
- 多行输入与聊天编辑器独立处理；聊天输入框不纳入普通表单规范。
- 设置/取消/输入密码弹窗（`GamePasswordDialog`）仅保留动态消息，不显示 subtitle 与 hint 文案；竖屏高度 `WRAP_CONTENT`，横屏 90% 屏高 + ScrollView。颜色一律走 `LauncherTheme` 工厂。
- 选择器入口可复用表单外观，但保留“选择目录/封面/引擎”等业务语义，不能伪装为保存按钮。
- 列表功能行按语义区分：设置行、主题选择行、聊天入口行、游戏内容项各自保留原行高与信息层级。

### RecyclerView 与滚动容器

- 固定行数的列表必须显式 `setHasFixedSize(true)`；长列表用 `setItemViewCacheSize` 或共享 `RecycledViewPool` 提升复用率。`onBindViewHolder` 内不得创建 Listener/对象，应通过 `holder.adapterPosition`/`bindingAdapterPosition` 复用回调。
- ViewHolder 内不得定义 `dp()` 等工具方法，统一走 `LauncherTheme.dp(itemView.context, ...)`；Adapter 内同理（违规存量见重构计划文档）。
- 包含 RecyclerView 或可滚动子 View 的页面根滚动容器必须用 `NestedScrollView` 而非 `ScrollView`，避免嵌套滚动手势冲突；RecyclerView 作为滚动容器的子项时设 `nestedScrollingEnabled=false` 并给固定/包裹高度，禁止 `wrap_content` 撑满全量数据。
- ImageView 必须显式指定 `scaleType`，不依赖默认 `fitCenter`：游戏封面/头像统一 `centerCrop`；图标按钮 `center`/`centerInside`；含透明边的装饰图 `fitCenter`/`centerInside`。

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
- **PopupWindow / 自建菜单宽度兜底**：竖屏自建 `PopupWindow`/`AlertDialog` 同样禁止裸 `dp(N)` 宽度字面量，必须做屏幕宽度兜底（`min(densityWidth, screen-48dp)`）；优先扩展 `LauncherDialogFactory` 的 `showStandardActionChoices` 而非在 Activity 手拼菜单（违规存量见重构计划文档）。

## 6. PadUi 横屏规范

### 适用范围

- 本章同时约束 `PadUi`（Pad 横屏）与 `HDModel`（HD 横屏）两个横屏场景。HD 横屏页面（`HdSettingsFragment`、`HdModeActivity`、`HdSaveManagerFragment`、`HdHomeFragment` 等）的弹窗、取色、宽度兜底与 PadUi 一致，不要因为命名不同而回退到竖屏 `LauncherDialogFactory`。
- 横屏与竖屏的弹窗工厂严格分库：竖屏页面只用 `LauncherDialogFactory`，横屏页面（Pad/HD）只用 `PadDialogFactory`。HD 横屏页面调用竖屏 `LauncherDialogFactory` 会导致宽度/字号/缩放不一致，属于必纠缺陷。

### HD 页面构成与承载模式

HD 大屏横屏由 `com.apps.HDModel.HdModeActivity` 外壳承载：

- 左侧导航栏 `hdNavigationRail`（76dp，`launcher_white_card` 背景），主内容容器 `hdFragmentContainer`（`marginStart=90dp`、`launcher_white_card` 24dp 圆角、`clipToOutline`+`clipChildren`+`clipToPadding`、`padding=12dp`），背景装饰 `LauncherParticleView`。
- `LauncherInsetsHelper.applyInsets(binding.root, binding.hdModeContent)` 统一处理系统栏 insets；窗口走 HD 专用 `configureLandscapeWindow()`（全出血、系统栏着色为页面背景、刘海短边裁切、关闭对比度增强），与 `LauncherEdgeToEdgeHelper` 语义不同，属豁免实现。
- 5 个主导航项（HOME/LIBRARY/MANAGE/ACCOUNT/SETTINGS）经 `showRootFragment()` 用 `replace` 进 `hdFragmentContainer`，按左右方向选进出场动画；切换栏目或弹出回退栈时先 `popBackStackImmediate(..., INCLUSIVE)` 清残留详情页。
- 弹窗统一经 `LauncherDialogRouter`（沿 ContextWrapper 链识别 `HdModeActivity` 壳 → `PadDialogFactory`；竖屏 → `LauncherDialogFactory`），新业务 Fragment 弹窗禁止直接调用 `LauncherDialogFactory`。

HD 页面有三种承载模式：

1. **主导航根 Fragment**：`HdHomeFragment`/`HdGameLibraryFragment`/`HdManageFragment`/`HdAccountFragment`/`HdProfileFragment` 直接 `replace` 进 `hdFragmentContainer`，继承对应竖屏 Fragment，覆写 `usePortraitXxxScaler()=false`、`applyXxxSystemBarInsets()=false`，用独立 `fragment_hd_xxx.xml` 分栏布局（复用竖屏业务逻辑与交互）。`HdSettingsFragment` 为特例：直接继承 `Fragment`（非竖屏 Fragment），自行构建横屏布局，不覆写上述两方法。
2. **子 Fragment 嵌入明细容器**：各根 Fragment 实现 `HdEmbeddedActivityOwner` 接口（接口仅声明 `closeEmbeddedActivity(child: Activity? = null): Boolean`），并在各自内部实现私有 `showChildFragment(tag, frag)`（`childFragmentManager.replace(R.id.hdXxxDetailContainer, frag, tag)` + 进出动画）承载竖屏子 Fragment（如 `LauncherAppSettingsFragment`/`LauncherThemeMenuFragment`/`LauncherProfileEditFragment` 等）；明细容器背景 `launcher_chat_option_bg`（10dp 圆角）+ `clipToOutline`；`closeEmbeddedActivity()` 按 tag 移除子 Fragment 并 return true。返回键由 `HdModeActivity.onBackPressed` → `currentEmbeddedOwner()?.closeEmbeddedActivity()` 优先关闭子 Fragment。
3. **主容器回退栈压栈**：`HdSaveManagerFragment`（`showSaveManagerFragment`）、`LauncherGameEditFragment`（`HdModeActivity.showDetailFragment(fragment, tag)`）用 `replace + addToBackStack` 压栈，返回键弹回、保留左侧导航；页面自关闭对薄宿主调 `finishXxx()`、对 HD 回退栈用 `activity.onBackPressedDispatcher.onBackPressed()` 弹栈。注：`LauncherKrkrSettingsFragment` 存在双重承载——库页（HdGameLibraryFragment）经 `showDetailFragment` 压栈，管理页（HdManageFragment）作为子 Fragment 嵌入明细容器，两处入口按 L237 承载模式选择原则各自成立。

### 竖屏 → HD 适配规范

从竖屏页面新增 HD 适配时，遵循以下步骤与约束：

- **承载模式选择**：主导航页做根 Fragment（模式 1）；从某页菜单/长按进入的独立详情页做子 Fragment（模式 2，随宿主返回统一关闭）或回退栈压栈（模式 3，需独立返回语义时）。
- **文件归属与命名**：HD Fragment 放 `com.apps.HDModel`，命名 `HdXxxFragment`，继承对应竖屏 Fragment（复用业务逻辑与交互，仅改布局为横屏分栏）；新布局 `fragment_hd_xxx.xml` 用 `snake_case`。
- **关闭竖屏专用适配**：覆写对应竖屏 Fragment 的 `usePortraitXxxScaler()=false`、`applyXxxSystemBarInsets()=false`；禁止把竖屏平板缩放器（`LauncherTabletPortraitScaler`）直接套到 HD。
- **明细容器承载子 Fragment**：实现 `HdEmbeddedActivityOwner`（接口仅含 `closeEmbeddedActivity`）；各根 Fragment 内部实现私有 `showChildFragment(tag, frag)`，统一用 `childFragmentManager.replace(R.id.hdXxxDetailContainer, ...)` + 进出动画；`closeEmbeddedActivity()` 按 tag 移除并 return true；`onViewCreated` 里 `detailContainer = view.findViewById(...)`，`onDestroyView` 置空并解除监听。
- **弹窗**：新业务 Fragment 弹窗一律经 `LauncherDialogRouter` 自动路由 Pad/竖屏工厂；HD 下禁止直接调 `LauncherDialogFactory`，避免宽度/字号/缩放不一致（必纠缺陷）。
- **嵌入式页面圆角**：嵌入主容器/明细容器的页面，根布局若自带不透明背景（如 `launcher_bg`）会盖住宿主容器圆角白卡；在 `onViewCreated` 中 `if (activity is HdModeActivity) view.background = null` 露出宿主圆角。主容器圆角 24dp（`launcher_white_card`），明细容器圆角 10dp（`launcher_chat_option_bg`）。
- **回退栈压栈**：详情页经 `HdModeActivity.showDetailFragment(fragment, tag)` 压栈；关闭时对薄宿主（竖屏 Activity）调对应 `finishXxx()`、对 HD 回退栈用 `activity.onBackPressedDispatcher.onBackPressed()` 弹栈。若压栈用 `replace` 导致根 Fragment 重建，须在基类用 `onSaveInstanceState` 保存待刷新状态（如 `pendingEditGameId`）并在 `onCreate` 恢复。
- **ActivityResult**：HD 子 Fragment 自行注册 `registerForActivityResult`，不再经 LocalActivityManager/宿主转发（`LocalActivityManager` 已废弃禁用）。
- **复用竖屏子 Fragment**：明细容器优先复用既有竖屏 Fragment，不新建 HD 专属副本；仅需特定 HD 背景/视觉时通过构造参数或运行时判断适配（参照 `ResourceStationFragment.newInstance(hdEmbedded=true)`、本文件 §8 大文件拆分与重复删除纪律）。
- **长按 Action 迁 HD**：竖屏长按弹窗触发的独立 Activity（如编辑游戏/引擎设置）迁为薄宿主 + Fragment 后，HD 侧在对应根 Fragment 覆写入口方法（基类改 `protected open`），经 `showDetailFragment` 压入主容器，而非跳独立 Activity。

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
- `PadSettingsActivity` 的账户确认、账户加载、结果提示应继续使用 Factory；设置页的三等分操作按钮通过 inline API 统一。

### 必须保留专用实现的场景

- 同步进度：非取消、保留 `sync_progress` tag 与后台更新链。
- 文件访问权限：保留 Android 版本分支和系统设置跳转。
- 游戏详情：保留 288dp 详情容器；长 URI/包名通过 `TextView` 的 `maxLines` + `ScrollingMovementMethod` 实现内部滚动，内容短时正常显示、超长可垂直滚动查看。

### 弹窗实现纪律

- 游戏详情/播放状态/等共享弹窗的 UI 元素 helper（`createLauncherDialog`/`createDialogRoot`/`createDialogTitle`/`createDialogButton`/`createDialogCancelButton`）位于 `com.apps.game.GameActionMenuFactory`。这些 helper 仅供 `GameActionMenuFactory` 的共享弹窗使用；新增同类弹窗必须直接用 `PadDialogFactory` 的对应 `show*()` API，不要扩展这些 helper 或手写 root/title/button。
- 专用实现的弹窗宽度必须通过 `PadDialogFactory.dialogWidthPx(context, widthDp)` 做屏幕宽度兜底（`min(densityWidth, screen-48dp)`），不要直接传 `dp(288)`/`dp(270)`，避免极窄屏溢出。竖屏 `LauncherDialogFactory` 内部已做兜底；任何自建弹窗或菜单（含 `PopupWindow`、`AlertDialog`）在指定宽度时也必须做屏幕宽度兜底，禁止裸 `dp(288)` 等固定宽度字面量。
- `showConfirm`（双按钮确认，288dp）使用 inflate 的 `dialog_launcher_confirm` 布局实现水平并排按钮；`showStandardConfirm`（普通确认，270dp）使用程序化构建的垂直按钮。两者是不同弹窗类型，水平/垂直差异是有意设计，不是实现不一致；不要为统一而合并。
- 同步确认、账户确认归类为"普通确认"，使用 `showStandardConfirm`（270dp）；只有需要水平双按钮的启动确认才用 `showConfirm`（288dp）。
- 粒子样式选择使用 `PadDialogFactory.showSingleChoice`（可滑动单选列表），通过 `checkedIndex` 表达已选状态、末项"关闭动态粒子"表达关闭操作、回调内 `Toast` 反馈；不要再手写选项行。
- **跨上下文共享宽度兜底的豁免**：`GameActionMenuFactory` 等被竖屏手机 / Pad 横屏 / 平板竖屏多上下文调用的共享弹窗工厂，允许统一使用 `LauncherDialogFactory.dialogWidthPx(ctx, widthDp)` 做宽度兜底，不强制按调用方上下文路由竖屏 / Pad 工厂。理由：(1) `LauncherTabletPortraitScaler.scaleFor` 在 Pad 横屏返回 1，宽度值正确无错误缩放；(2) 平板竖屏下宽度放大但内容 padding/字号未同步缩放，属已知视觉比例差异，默许接受；(3) Pad 横屏边距 32dp（vs §6 规范 48dp）偏差 16dp，功能无溢出，默许接受。后续若需严格按调用方路由，应在 `ActionMenuConfig` 或 `SubDialogFactory` 接口注入宽度计算函数引用，不在 `setDialogContent` 内做上下文判断。

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
7. 规范一致性 grep 检查（应全部为空或仅命中合规位置）：

```bash
# 分层：com.core 不依赖 com.apps
! grep -r "import com.apps" app/src/main/java/com/core/
# dp() 副本：仅 LauncherTheme 内部允许
grep -rn "fun dp(\|int dp(" app/src/main/java/com/apps --include="*.kt" --include="*.java" | grep -v "LauncherTheme"
# 内联窗口配置：应全部走 LauncherEdgeToEdgeHelper
grep -rn "configureEdgeToEdgeWindow\|FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS" app/src/main/java/com/apps --include="*.kt" --include="*.java" | grep -v "LauncherEdgeToEdgeHelper"
# HD/Pad 横屏错用竖屏弹窗工厂
grep -rn "LauncherDialogFactory.show" app/src/main/java/com/apps/HDModel app/src/main/java/com/apps/PadUi
# 裸 startActivity(ACTION_VIEW) 未走 LauncherUrlOpener
grep -rn "ACTION_VIEW" app/src/main/java/com/apps --include="*.kt" --include="*.java" | grep -v "LauncherUrlOpener"
# 废弃 insets 属性（应仅命中已登记特化存量：LauncherProfileFragment/LauncherLibraryFragment/LauncherRegisterFragment/AvatarCropActivity）
grep -rn "systemWindowInset\|getSystemWindowInset" app/src/main/java/com/apps --include="*.kt" --include="*.java"
```

## 8. `app/src/main/java` 通用代码规范

本章适用于 `com.apps.*` 与 `com.core.*`。第 1～7 章仍是 UI 改动的优先规则；如本章与既有实现冲突，新代码遵循本章，存量代码只在相关功能改动时渐进迁移，禁止为满足格式要求进行无业务价值的大规模重写。

### 分层与包依赖

- `com.apps.*` 是展示层：Activity、Fragment、Adapter、View、UI 状态及导航协调代码。它可以依赖 `com.core.*`，但不直接承担持久化、网络协议解析或可独立复用的领域决策。
- `com.core.*` 是业务与平台层：model、data、net、sync、scanner、importer、launcher、metadata、translation 的非 UI 能力及通用 util。`com.core.*` 禁止 import `com.apps.*`，也不得直接持有或启动 `com.apps.*` 的 Activity、Fragment、Dialog、Theme 等 UI 对象。
- **引擎壳层 Activity 启动豁免**：`com.core.launcher` 启动引擎壳层 Activity（`com.core.tyrano.TyranoActivity` 等 engine 子模块）及外部模拟器包（`com.akira.tyranoemu.remote.*`、`org.tvp.kirikiri2.*`、`com.yuri.onscripter.*`）是允许的边界例外，因为引擎启动天然需要起 Activity。但禁止启动 `com.apps.*` 的 UI Activity；UI 跳转由 `com.apps` 调用方在收到 core 启动结果后自行发起。
- **core 内 Dialog/View 持有禁令**：`com.core.*` 不得直接 `new AlertDialog`/`WindowManager.addView` 构建弹窗或悬浮 View。确需平台覆盖层时，走 `LauncherUiBridge` 扩展方法由 `com.apps` 实现，或将该 Service 迁至 `com.apps`。core 内 Service 通过 `WindowManager` 渲染平台覆盖 View 的归属需在后续阶段界定（违规存量见重构计划文档）。
- UI 所需的 core 回调通过接口、状态模型或事件向上交付；core 不反向调用 UI。Application 级 UI 初始化应移到 `com.apps` 的启动协调代码，避免 core 成为 UI 的依赖方。`com.core.launcher.LauncherUiBridge` 是 core 读取 Launcher 主题色、重启入口和覆盖层确认弹窗的边界；`com.apps.LauncherApplication` 负责注册具体实现。
- 新包名一律小写，使用 `lowercase`；新增 Pad 代码放入 `com.apps.padui`。用户数据导出/导入、游玩记录缓冲、会话映射等可复用能力放入 `com.core.userdata`；展示入口只从 `com.apps.*` 调用 core API。既有 `PadUi` 等大小写包仅在其周边改动时迁移，迁移时同步更新所有引用。
- 本地智能体的网络协议、MCP、运行时编排、工作区文件操作和持久化能力归属 `com.core.agent.net`、`com.core.agent.runtime`、`com.core.agent.workspace`、`com.core.agent.store`。`com.apps.agent` 只保留 Activity、Adapter、自定义 View 等展示层代码。
- 按功能归属文件：一个 feature 的 UI、状态和协调代码放在对应 `com.apps.<feature>`；可复用的领域能力放在对应 `com.core.<feature>`。不要仅为“工具类”而创建无业务语义的公共包。

### 状态、线程与生命周期

- 新增 Kotlin 异步代码使用结构化协程：页面相关任务使用 `lifecycleScope`，ViewModel 任务使用 `viewModelScope`，并显式指定 `Dispatchers.IO`、`Default` 或 `Main`。不得使用 `GlobalScope`。
- `AppExecutors` 是 Java 及存量调用的兼容层；新增 Kotlin 业务代码不新增 `AppExecutors + runOnUiThread` 链路。迁移旧代码时优先按所在生命周期替换，而不是在无生命周期的静态对象中创建 scope。
- 后台任务不得直接更新 View；回到主线程前先确认 Activity/Fragment 仍有效。Fragment 的 View 任务必须在 `onDestroyView()` 后自动取消或不再访问 binding。
- **回主线程守卫**：所有回主线程执行的 lambda 体首行必须加守卫——Activity 为 `if (isFinishing || isDestroyed) return`，Fragment 为 `if (!isAdded || binding == null) return`。覆盖范围包括 `runOnUiThread { ... }`、`AppExecutors.mainThread().execute { ... }`、`RxMainScheduler.post { ... }`、`mainQueue.post { ... }`/`getMainQueue().post(...)`，以及 core Bridge 通过 `postToMain` 上行回调的实现方 lambda。仅检查 `getActivity() != null` 不足以防止 Activity finishing/destroyed 后的 UI 更新崩溃。存量缺失守卫的文件清单见重构计划文档，在相关功能改动时一并补齐。
- Repository/Bridge 返回领域结果或 `Result`；UI 层只负责加载、成功、空态和失败态渲染。不要让 Activity/Fragment 同时处理 HTTP、文件读写、持久化和复杂业务决策。
- **UI 层文件 IO 下沉**：Activity/Fragment/Adapter 不得在主线程执行 `ContentResolver.openInputStream`、`BitmapFactory.decode*`、`Files.copy`、`File.listFiles` 等可能阻塞或 OOM 的 IO。头像解码、封面拷贝、URI 可读性探测等必须切到 `AppExecutors.runOnIo`/`Dispatchers.IO`，结果回主线程时按下方守卫规则校验生命周期。文件写入下沉到 `LauncherScanBridge`/`LauncherImageBridge` 等 core 层 API，UI 层只传入来源 URI 与目标路径，不直接持有流。批量 IO 必须带大小上限（参考 ImporterIO 的 `MAX_ENTRY_BYTES`/`MAX_TOTAL_BYTES`/`MAX_ENTRY_COUNT`），避免 ZIP/压缩放大攻击。
- **core 内所有 IO 路径强制字节上限**：`com.core.*` 内所有 `read()`/`FileInputStream`/`HttpURLConnection`/`BitmapFactory` 读取必须有显式字节上限（参照 `ImporterIO.MAX_ENTRY_BYTES`/`LauncherUserData.readText(file, maxBytes, label)` 模式），禁止无界 `read()`/`available()`；截图/Bitmap 路径除像素维上限外补字节上限。`LauncherUserData` 的 `MAX_SETTINGS_BYTES`/`MAX_PLAY_SQL_BYTES` 等各自定义的限额保留，命名后续可统一。
- **postDelayed 清理**：凡 `Handler`/`RxMainScheduler.postDelayed`/`mainQueue.postDelayed` 的延迟任务必须持有 disposable/callback 引用，并在 `onDestroy`/`onDestroyView` 清理；`View.postDelayed` 可豁免（View 销毁时自动清理）。lambda 内仍须加回主线程守卫作为兜底。参照 `LauncherPublicChatFragment.cancelHeartbeat`、`GameSessionController.removeCallbacks`、`LauncherParticleView.removeCallbacks` 为合规实现。
- **core 长生命周期监听器释放**：`com.core.*` 内 `FileObserver`/`HandlerThread` 须提供显式 `stop()`/`release()`/`quit()` 入口并在 Service/Launcher 销毁时调用，避免泄漏（具体位置见重构计划文档）。
- 共享状态仅在确有并发访问时使用 `@Volatile`、锁或原子类型；说明其保护的状态与线程边界，避免以全局可变单例代替状态所有权。`@Volatile` 仅用于跨线程可见性需求；只在 `@Synchronized` 方法内访问的字段、或仅主线程访问的字段不需要 `@Volatile`，误加会误导读者以为存在跨线程访问。已识别的 `@Volatile` 误用存量见重构计划文档，相关功能改动时删除。
- **长运行任务防重入**：导入、初始化、同步等长运行任务必须使用显式进行中标志（如 `importInProgress`）阻止重复触发；入口先检查标志置位则直接返回，完成/失败收尾时复位。禁止依赖 UI 禁用态或隐式时序防重入。
- **临时目录/临时文件显式清理**：临时目录用显式递归删除（`deleteOnExit()` 不可靠），在 `finally` 块与取消回调中执行；解压/解包类操作先做大小/条目数上限检查再写入，防止部分写入残留。

### 异常、日志与数据处理

- 禁止捕获 `Throwable`，除非处于明确的进程边界、回滚清理或日志兜底点，且必须说明原因；不得吞掉 `CancellationException`、`InterruptedException`、`Error` 等不可恢复信号。
- 只捕获可预期的具体异常；失败必须返回给调用方、显示合适的用户提示或写入 `DevLogger`。允许忽略的异常必须在紧邻 catch 处说明为何可安全忽略。
- 收窄异常时需分析具体 API 的实际异常契约，不能只看 catch 语法是否通过：受检异常之外，确认方法是否返回 null、抛出 `SecurityException`/`IllegalArgumentException` 等运行时异常。例如 `ContentResolver.openInputStream()` 可能返回 null 或抛 `SecurityException`（`content://` 授权过期），只捕获 `IOException` 会导致后台线程 NPE 或未捕获异常崩溃。对可能返回 null 的流必须显式判空，对可预期的运行时异常应一并捕获（`catch (IOException | SecurityException e)`），并在 catch 处说明失败兜底行为（提示用户、清除失效配置、回退默认值）。
- **常见 catch 收窄对照**：`startActivity`/`startActivityForResult` → `ActivityNotFoundException`；`PackageManager.getPackageInfo` → `PackageManager.NameNotFoundException`；`SharedPreferences`/`SQLiteDatabase` 操作 → `SQLException`/`IllegalStateException`；`Shizuku` 调用 → `ShizukuNotInstalledException`/`SecurityException`；`Intent.parseUri`/`Uri.parse` → `URISyntaxException`/`IllegalArgumentException`；`SimpleDateFormat.parse` → `ParseException`；`Class.forName`/`Method.invoke` → `ReflectiveOperationException`；文件 IO（`copy`/`move`/`delete`） → `IOException`/`SecurityException`；`DocumentFile.listFiles()`/`File.listFiles()`/`File.delete()` 文件树遍历 → `catch (Exception)` 或 `catch (IOException | SecurityException)`，不得 `catch (Throwable ignored)` 静默吞掉；`Error`/`OutOfMemoryError` 在任何路径都必须传播不得捕获，bitmap decode 需显式 `catch (OutOfMemoryError) { throw e }`。`throw new Exception` 一律改为具体异常类型（`IOException`/`IllegalStateException`/`IllegalArgumentException`）。违规存量位置见重构计划文档。
- **CancellationException 重抛强制模式**：协程 `catch` 块若捕获 `Exception`/`Throwable`，必须先 `catch (e: CancellationException) { throw e }` 重抛，再 `catch (e: Exception)` 处理业务异常，否则会吞掉协程取消信号。合规范本与违规存量见重构计划文档。
- 不使用 `!!` 作为常规控制流。可空值优先使用 `?.`、`?:`、提前返回或 `requireNotNull`（仅用于违反内部不变量的情形）。
- 时间展示统一复用 `TimeFormatUtil`；网络协议或导入格式解析可使用专用 `SimpleDateFormat`，但须显式指定 `Locale` 与格式来源。不要在 UI 中重复实现通用展示格式。
- 用户可见文本优先放入资源；日志不得包含 access token、API key、密码或完整的私有路径。涉及外部输入、文件与 URI 时先校验可读性、边界和编码。

### 文件职责与可维护性

- 一个类/文件只保留一个主要职责。页面拆分为 UI 绑定与渲染、状态/事件协调、业务操作；Repository 拆分为查询、写入、迁移或外部源适配等明确职责。
- 单文件超过约 500 行、或同时包含 UI、线程调度、I/O 和领域规则时，应在下一次相关功能改动中拆分。优先提取可独立测试的 Controller、Use Case、Formatter 或数据源，不改变对外行为。
- **大文件拆分模式**：按职责切片而非按行数均分。典型拆分维度——UI 渲染层（Toolbar/Gesture/Paging/Avatar 子 Controller）、领域选项层（Catalog/Resolver，参照 `EngineOptionCatalog`/`EnginePackageResolver`）、工厂子 object（`LauncherDialogFactory` 拆 Confirm/Choice/Loading/Update）。拆分后原文件作为薄协调层保留，对外 API 签名（`@JvmStatic`/`@JvmField`）不变，调用方零修改。禁止为拆分而拆分：若文件虽长但职责单一且改动频率低（如单一渲染器、单一解码器），可保留并注释说明。
- **死代码清理**：`private`/`internal` 方法无调用方、`onPause()` 等仅 `super` 的空实现、被新实现取代但未删的旧类，属于必清死代码。清理前必须用全局搜索确认无反射/资源/Manifest 引用；对存疑项标注验证结论后再决定。删除前执行验证命令：`grep -rn "ClassName" app/src/main --include="*.kt" --include="*.java" | grep -v "ClassName\."` 确认无业务调用，`grep -rn "ClassName" app/src/test app/src/androidTest` 确认无测试引用；删除后运行 `./gradlew :app:assembleDebug` 与 `git diff --check` 确认无断链。已确认死代码清单见重构计划文档。
- **废弃 API 禁用**：禁止新增使用 `LocalActivityManager`/`ActivityGroup`/`Fragment.userVisibleHint` 等已废弃 API。存量使用 `LocalActivityManager` 嵌套 Activity 的 Fragment 应在相关功能改动时迁移到子 Fragment 或 NavComponent，迁移时一并瘦身因转发 `dispatch*` 调用而膨胀的生命周期方法（具体清单见重构计划文档）。
- **跨页面共享领域逻辑的提取**：当两个及以上 Activity/Fragment 包含相同的领域数据组装（如引擎选项列表、包名路由、子类型匹配）或相同的工具方法（如 dp 转换、窗口配置、URL 打开）时，必须提取为 package-private 或 internal 的独立类/object，不在每个页面内保留副本。参照 `LauncherEdgeToEdgeHelper`（共享窗口配置）、`LauncherTheme.dp`（全模块共享 dp 转换）。`EngineOptionCatalog`/`EnginePackageResolver` 是共享引擎选择逻辑的提取范例，但当前仍为 Java 存量文件，应迁 Kotlin `object` + `@JvmStatic`（见 §2 语言约束）。提取后调用方零行为变更，新功能只扩展提取类不回退到内联。
- **Activity 的 companion 不得承担全局静态职责**：偏好读写、主题判断、UI 模式包装、Splash 资源、导航样式等与 Activity 实例无关的静态能力，必须放在独立 `object`（参考 `LauncherPreferences`、`LauncherThemeStyle`、`LauncherUiMode`、`LauncherSplash`、`LauncherNavRenderer`）。`LauncherActivity` 的 companion 只作为兼容委托层保留既有 `@JvmStatic` 签名，不得新增实现；新增调用方直接引用对应 object，不经 companion 转发。Activity 实例逻辑（生命周期、路由、装配）与渲染/状态管理应分离，导航渲染等大块 UI 逻辑抽成持有 Activity 的协调类，避免单 Activity 文件膨胀。
- **object 间禁止循环依赖**：低层工具 object（`LauncherNavigationMetrics`、`LauncherPreferences` 等）不得反向依赖高层 `LauncherActivity`。SharedPreferences 名等共享常量统一以 `LauncherPreferences.APP_PREFS` 为单一来源，不通过 `LauncherActivity.APP_PREFS` 回跳，避免形成模块级循环引用。新 object 只依赖同层或更底层的 object/`Context`。
- **常量委托的约束**：`const val` 无法委托到另一 object 的属性，大规模重构时可在兼容层保留字面量副本并注明主源；`@JvmField val` 可用 `@JvmField val X = LauncherY.X` 形式委托以保持单一来源。重构时优先保留 `@JvmStatic` 委托方法签名与 `@JvmField`/`const val` 常量名不变，让既有调用方零修改、分步迁移，降低回归风险。
- **持有 Activity 的渲染/协调类**：导航渲染、Splash 等 UI 协调类需要访问 Activity 状态时可持有 Activity 引用，但须满足：生命周期严格被 Activity 包裹（在 `onCreate`/`showLauncherContent` 等创建，Activity 销毁即释放）；对 Activity 私有字段的访问通过 `internal` getter 暴露（如 `internal val launcherBinding`），不直接改为 `public`；`lateinit` 字段必须在访问前用 `binding != null` 等守卫保证已初始化，避免在 splash 等异步阶段触发未初始化访问。
- 新增公共 API 需有 KDoc/Javadoc：说明用途、线程要求、可空约定、失败方式及 Java 互操作约束（若适用）。不要为显而易见的私有实现添加噪声注释。
- 保持 import 分组和格式化一致；不引入与文件现有语言无关的 Java/Kotlin 互操作包装。新业务类优先 Kotlin，Java 文件仅维护既有实现或确有互操作必要的代码。

### 工具方法单一来源（com.core 与引擎侧）

- **按包名启动**统一用 `com.core.launcher.PackageLauncher.launchPackage`（阶段 124 单源，`ExternalGameLaunchers.launchPackage` 委托），禁止调用方自拼 Intent 回退链。
- **内置引擎 URI→路径解析**统一用 `com.core.launcher.ScriptEngineLaunchers`（`uriToFilePath`/`stripFileScheme`/`resolve*GameDirectory`/`resolve*SaveLocation`/`build*Intent`，tree/document 混合 SAF URI 解析），KRKR/Artemis/ONS/Tyrano/Winlator 路径解析与 `GameRepository.normalizeRootUriKey`（companion @JvmStatic，rootUri 身份键单源，games.root_uri_key 唯一索引来源）同思路。
- **引擎启动**：Java 调用方稳定门面用 `com.core.launcher.EmulatorLauncher` companion（全部 @JvmStatic 委托，新增启动能力只扩展实现类不新增门面方法）；策略注册/分发用 `com.core.launcher.ExternalGameLaunchers`（`registerStrategy`/`launchGame`）；各引擎实现单源——`KrkrLauncher`（含 `normalizeEngineVersion` 版本归一化）、`ArtemisLauncher`（含 `fallbackStage`；`stopSaveSync` 为 §8 监听器释放原则落地）、`WinlatorLauncher`（`.desktop`/`.exe` 解析，`isWinlatorPackage` 委托 `EnginePackages`）、`HandheldLaunchers`（PSP/Citra/Eden）、`EngineSaveLocations`（各引擎实际存档目录聚合）；包名/关键词判定统一用 `EnginePackages`（含 `WINLATOR_PACKAGE_KEYWORDS`/`isWinlatorPackage`/`isInternal*`）。
- **引擎类型字符串解析**统一用 `com.core.model.EngineType.fromString`（未知回退 UNKNOWN，与 `HomeStyle.fromStorage` 同模式）。
- **存档传输**：`com.core.data.SaveFileUtils`（复制/校验/`safeZipEntryName` 路径穿越防护/`rejectGamePayloadEntry` 原语 + `MAX_SAVE_ZIP_BYTES`/`MAX_SAVE_ZIP_FILES`）、`SaveZipTransfer`（ZIP 打包/解压）、`SaveDocumentTransfer`（SAF 树↔本地目录复制），经 `GameSaveFileManager` 组合使用。
- **同步/备份编解码**统一用 `com.core.sync.SyncSnapshotCodec`（`snapshotToText`/`compressGzip`/`decompressIfGzip` + `MAX_REMOTE_SNAPSHOT_BYTES`/`MAX_LOCAL_BACKUP_BYTES` 解压放大防护）。
- **IO 原语与临时清理**：带上限读取、`registerTempDir`/`cleanupAllTempDirs`/`deleteRecursively` 统一用 `com.core.importer.ImporterIO`；Bitmap 消费侧字节上限用 `com.core.util.BoundedInputStream` + `MAX_BITMAP_SOURCE_BYTES`（挂 §8 core IO 字节上限原则）。
- **游玩记录/会话**：上传缓冲与本地↔服务端会话映射用 `com.core.userdata.LauncherPlayRecords`；`com.core.data.PlaySessionRepository` 与 `GameRepository` 共享同一 `YukiDatabaseHelper` 实例（`DB_NAME`/`DB_VERSION` 单源）保证跨表事务原子性；游玩状态归一化用 `PlaySessionRepository` 顶层 `normalizePlayStatus`。
- **数据层访问**：com.apps 新代码经 `com.core.launcherbridge.LauncherRepositoryBridge`（GameRepository 门面）/`LauncherScanBridge`/`LauncherGameLaunchBridge`（启动 gate + play_session 生命周期）/`LauncherModuleBridge`（外部插件包名判定 + 模块开关）访问 core，禁止直接 import `com.core.data` 内部类；`LauncherUiBridge` 是 core 读主题色/重启/覆盖层确认的边界。
- **诊断/日志**：启动/存储事故诊断时间线用 `com.core.diagnostics.GameDiagnostics`；开发者日志统一用 `com.core.util.DevLogger`（日志输出单源）。
- **工作区/agent 域**：文本编码探测用 `com.core.agent.workspace.WorkspaceEncoding`、JSON 根/指针解析用 `WorkspaceJson`、模型相对路径校验/敏感文件判定用 `AgentRelativePath`。
- **偏好键镜像与常量**：com.core 侧偏好键统一用 `com.core.prefs.LauncherMainKeys`/`ScanRootKeys`、`com.core.launcher.EngineSaveKeys`（主源见 §8 跨模块技术债口径）；备份文件命名前缀用 `com.core.CoreBackup.FILE_PREFIX`；`LauncherUserData.readText(file, maxBytes, label)` 为带字节上限文件读取模式参照。
- **Importer 时间解析**：`com.core.importer.ImporterService.parseIsoTime`（与 `TimeFormatUtil` 展示格式化互补）。
- **UI 缩放偏好**：`com.core.util.UiScaleUtil`（`getUiScale`/`setUiScale`/`wrap`/`clamp`）。

### 自动检查与提交要求

- 新增或迁移代码应通过 Android Lint、Kotlin 格式检查和静态分析；在引入工具前，至少执行构建与 `git diff --check`。建议逐步接入 Ktlint/Spotless 与 Detekt，并先对新增问题设为阻断。
- 涉及分层调整时，检查 `com.core` 不新增 `import com.apps`；涉及线程调整时，验证页面销毁后没有 UI 更新或任务泄漏。
- **跨模块技术债处理口径**：`com.core` 内既存的 `"yukihub_prefs"`、`"kr_engine_version"`、`LauncherUserData.MAIN_PREF_KEYS` 偏好键等与 `com.apps.LauncherPreferences` 重复的字面量属于历史技术债，非阻塞但不扩散。`com.core.CorePreferences` object 已设立作为 core 侧偏好名/键单一来源（主源在 `LauncherPreferences`，core 侧以镜像常量引用）；新增 `com.core` 代码必须引用该镜像常量，不得新增字面量。引擎包名路由字符串下沉到 `com.core.launcher.EnginePackages`（或 `EngineType` 伴生）作单一来源。批量清理作为独立技术债阶段处理，不与 UI 改动混在同一提交（具体位置与计数见重构计划文档）。
- 最低验证命令保持如下；涉及 core 逻辑时同时补充或运行对应单元测试：

```bash
! grep -r "import com.apps" app/src/main/java/com/core/
./gradlew :app:assembleDebug
git diff --check
```
