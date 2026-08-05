# `com.apps` 架构治理与规范对齐计划

> 基线：`app/src/main/java/com/apps/agent.md`（§1–§8）+ `project_memory.md` 硬约束
> 来源：多 agent 并行扫描 + 交叉核对协作
> 生成日期：2026-08-01｜最近校准：2026-08-03（5 agent 分域复核 com.apps + com.core 全目录，失实修正见 §4.7）

---

## 一、文档维护规范

本计划文档用于登记架构治理的阶段规划与执行结果，保持精简可读。新增/修改内容遵循以下规则：

1. **阶段完成登记格式**：一句话概括改动（改动要点 + 涉及文件）+ 交叉核对 PASS 数 + 编译验证结果。不再贴大段整改表格与逐条验证细节。
2. **已完成任务**：完成即压缩为「二、已完成任务总览」表中一行，不展开叙述。
3. **实机测试**：不在文档登记详细测试清单；仅把需要实机确认的风险点登记到「四、注意事项」的「需实机确认」小节。
4. **注意事项统一归集**：遗留项、技术债、警告、决策、观察项一律登记到「四、注意事项与遗留事项」，按类别归类，不散落在阶段记录里。
5. **阶段编号**：从 `阶段 10` 起顺延递增，新登记阶段使用最新编号（当前 `阶段 106`）。
6. **编译验证命令**（统一）：
   ```bash
   ./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process
   ```
7. **协作流程**：修改由修改 agent 执行 → 独立交叉核对 agent 只读审查 → 修改 agent 强制重编译 → 计划文档登记。

---

## 二、已完成任务总览（一句话概括）

| 阶段 | 一句话概括 | 状态 |
|------|-----------|------|
| Phase 0 分层纠偏 | `com.core`→`com.apps` 反向依赖清零；agent/ + LauncherUserData + TranslationSettingActivity 迁包；`LauncherUiBridge` 桥接就位 | ✅ |
| Phase 1 弹窗统一 | 6+ 处手拼弹窗迁工厂；`ManageHost` 改 fun interface；抽 `AgentConfigDialog`/`ExternalImportPreviewDialog`/`LauncherUrlOpener`；catch(Throwable) 清零 8 处；runOnUiThread 守卫补齐 | ✅ |
| Phase 2 常量+工具统一 | `LauncherPreferences`/`LauncherThemeStyle` 单一来源；`LauncherEdgeToEdgeHelper` 抽取（12 处）；dp() 11 处统一到 `LauncherTheme.dp`；`EngineOptionCatalog`/`EnginePackageResolver` 抽取 | ✅（`2d28b0d`） |
| 阶段 3 异常收尾+守卫 | catch(Throwable) 收窄 + runOnUiThread/RxMainScheduler 守卫补齐 + CancellationException 重抛确认 + 文件树遍历异常精确化（落点阶段 11/24/25） | ✅ |
| 阶段 4 手写弹窗清零 | AgentLlmConfigDialog 抽取、LauncherLaunchTargetPicker 工厂化、ExternalImportPreviewDialog 宽度兜底、compactChoice 选中态、AvatarCrop 取色；含 W1'–W3' 收尾审查（落点阶段 12） | ✅（4.4/4.6 除外，见剩余工作） |
| 阶段 5.1 大文件拆分 | 已拆 `GameActionMenuFactory`→`EditPlayTimeDialog`（511→348 行）；其余大文件待拆分（落点阶段 29） | 🔶 部分 |
| 阶段 5.2 UI IO 下沉 | 项 1 头像持久化下沉 `LauncherAvatarPersistence`（33）、项 3 封面复制共享桥（27）、项 4 PinnedGameShortcut 解码移 IO 线程（28）、项 5 isReadableImageUri 移 IO（27）、W2 个人页头像统一（34） | 🔶 部分 |
| 阶段 5.3 重复类删除 | 删除 `LauncherGameActionController.java`（558 行，连带消除 5 处 catch + 5 处未守卫） | ✅ |
| 阶段 6 HD/Pad 一致性+取色 | HD 弹窗工厂纠偏、HdHome 布局运行时拆装改 GONE、取色统一 `LauncherTheme`、`dialogWidthPx` 宽度兜底、nav 取色合并 `LauncherNavRenderer`（落点阶段 14） | ✅ |
| 阶段 7 文案+死代码+可读性 | 文案资源化（16 资源/13 处）、死代码清理、@Volatile 清理、`LauncherUrlOpener` 推广（落点阶段 15/18/20/21） | ✅ |
| 阶段 8 EdgeToEdge+跨模块常量 | 22 个 Activity 内联 `LauncherEdgeToEdgeHelper.apply`；`dpFloat` 统一；`yukihub_prefs` 13 处下沉 `CorePreferences`（落点阶段 15/18） | ✅ |
| 阶段 9.3 companion 常量下沉 | 5 个 intent 常量→`LauncherIntents`；`KEY_STORAGE_PERMISSION_ASKED`→`LauncherPreferences`；`SPLASH_MIN_DISPLAY_MS`→`LauncherSplash`（落点阶段 15） | ✅ |
| 阶段 9.4 nav 取色合并 | 三处 nav 中心图标取色合并 `navTone`/`applyNavTone`，未选中态统一 `textMuted`（落点阶段 14） | ✅ |
| 阶段 9.5 core 弹窗违规 | `LauncherGameLaunchBridge` AlertDialog 迁 `GameActionMenuFactory.showActiveGameInfo`（落点阶段 26）；OverlayTranslationService 悬浮窗归属未定（见注意事项） | 🔶 部分 |
| 阶段 9.6 监听器释放 | `ArtemisLauncher.stopSaveSync()` + `finishSession` 末尾调用（落点阶段 26） | ✅ |
| 阶段 9.7/9.8/9.11 异常与守卫 | CancellationException 重抛确认、文件树遍历 catch 精确化、守卫清单校准（落点阶段 11） | ✅ |
| 阶段 9.10 dp 副本 | 本地 dp() 副本 18 处清零（落点阶段 20/21）；`LauncherTheme` 保留唯一入口 | ✅ |
| 阶段 9.12 跨模块常量 | `yukihub_prefs`（15）、`kr_engine_version`（30）、引擎包名下沉 `EnginePackages`（31） | ✅（MAIN_PREF_KEYS 除外，见剩余工作） |
| 阶段 10 收尾审查 | BLOCKING `throw Exception`→`IOException`×2；W1–W6 修复（UrlOpener 推广、`LauncherUpdateFormatter`/`PadUpdateDialog` 抽取、import 清理、Pad 弹窗宽度对齐） | ✅ |
| 阶段 11 异常收窄+守卫 | com.apps 守卫/收窄 + com.core 文件树/静默空体精确化；交叉核对发现并修复 6 处（NPE 逃逸、空体遗漏等） | ✅ |
| 阶段 12 弹窗清零落地 | 4.1–4.7 + W1'–W3' 收尾审查（GameWorkspaceGateway/LauncherScanBridge catch 补漏、AgentLlmConfigDialog 宽度 API 提升） | ✅ |
| 阶段 13 进程回收诊断 | 内置引擎游戏被划掉连带杀主进程 = 系统进程管理行为（同 UID 整包回收），非代码 bug；缓解方案 A（onTrimMemory 释放内存）已实施（阶段 100），B/C 未选 | 📌 诊断完成（A 已落地） |
| 阶段 14 HD/Pad 一致性 | 取色语义等价验证、`dialogWidthPx` 兜底三处接入、HdHome 幂等守卫修正、nav 取色零 `Color.GRAY` 残留 | ✅ |
| 阶段 15 文案+prefs+常量 | 16 个 string 资源 + 13 处代码改 getString；`CorePreferences` 镜像 `APP_PREFS`；`LauncherIntents` 落地 | ✅ |
| 阶段 16 WARNING 收尾 | W1/W3/W4/W5 修复（ToolboxTool 命名、文件前缀常量、companion 委托删除、引擎标签资源化）；W2/W6 用户决策 | ✅ |
| 阶段 17 备份前缀下沉 | `"yukihub_backup_"`→`CoreBackup.FILE_PREFIX`，全仓字面量清零 | ✅ |
| 阶段 18 EdgeToEdge 内联+dpFloat | 22 Activity 薄包装内联；`LauncherTheme.dpFloat`；ResourceStation 白名单职责注释 | ✅ |
| 阶段 19 注释精度 | TranslationSettingActivity onCreate KDoc 职责链、ResourceStationActivity 路由注释重写 | ✅ |
| 阶段 20 dp 清零+ACTION_VIEW 收口 | 剩余 18 处 dp 副本全清；`LauncherModuleCompatibilityActivity` 裸 ACTION_VIEW 收口 UrlOpener | ✅ |
| 阶段 21 委托包装删除 | `GameActionMenuFactory` 两处 dp 薄包装删除，§1「仅 LauncherTheme 内部允许 dp()」com.apps 清零 | ✅ |
| 阶段 22 剩余警告决策 | LauncherNavRenderer half-up、UrlOpener 不带 BROWSABLE 均接受关闭（含 AOSP matchCategories 机制勘误） | 📌 已决策 |
| 阶段 23 §8 注释补齐 | com.core agent 包 catch(Throwable) 补 §8 说明注释（9 处主项 + 交叉核对补 3 处） | ✅ |
| 阶段 24/25 catch 收窄 | com.core launcherbridge 49 处（Batch A）+ launcher/scanner/diagnostics/sync 70 处（Batch B+C）；全 com.core 剩余 catch(Throwable) 14 处（agent 包 13 + diagnostics 1），均带 §8 说明注释合规保留 | ✅ |
| 阶段 26 §9.5/9.6 | `showActiveGameDialog` 迁移 + 删死资源 `core_got_it`；`stopSaveSync` 释放 | ✅ |
| 阶段 27/28 IO 下沉 | 项 3 封面共享桥（内存友好 + 720 封顶）；项 4 PinnedGameShortcut LruCache + 解码移 IO | ✅ |
| 阶段 29 EditPlayTimeDialog | 抽取 `EditPlayTimeDialog`（213 行），`GameActionMenuFactory` 5 行委托；交叉核对捕获 1 处 AlertDialog import 误写已回退 | ✅ |
| 阶段 30/31 9.12 单源化 | `KEY_KR_ENGINE_VERSION`→`CorePreferences`；引擎包名 57 处/14 文件→`EnginePackages`（17 常量 + 4 谓词） | ✅ |
| 阶段 32 收尾 WARNING | 阶段 26–31 审查 W1–W6 + INFO 修复（异常退化、单源化不完整、IO 异步副作用、冗余锁） | ✅ |
| 阶段 33 头像持久化下沉 | 新建 `LauncherAvatarPersistence.kt`（fd.sync + 原子替换 + 双偏好 commit + 缓存失效），主页/个人页/平板三处统一常量 | ✅ |
| 阶段 34 个人页头像统一 | 头像写入改走 `LauncherAvatarPersistence.copyAvatarToInternal`（W2），封面路径保留 `copyImageToInternal`；删 `syncAvatarToHome` 死代码 | ✅ |
| 阶段 35 弹窗宽度兜底+hint资源化 | AgentConfigDialog/LauncherCustomVndbSearchDialog 三处自建弹窗 setLayout 裸 dp 宽度改 `dialogWidthPx` 兜底；AgentConfigDialog 三处 hint 硬编码（0.0-2.0/1-50/16-1024）资源化三语（3 key×3 语言） | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 36 弹窗宽度兜底收尾 | GamePasswordDialog:219 裸 `dp(280)` 宽度改 `dialogWidthPx` 兜底；com/apps 全量 `setLayout(LauncherTheme.dp` 清零（0 命中） | ✅（交叉核对全 PASS + BUILD SUCCESSFUL） |
| 阶段 37 文档漂移+观察项清理 | agent.md §6 helper 位置更正为 GameActionMenuFactory；LauncherAiChatActivity unused import 删除；LauncherHomeFragment white 用途注释补齐 | ✅（交叉核对全 PASS + BUILD SUCCESSFUL） |
| 阶段 38 AvatarCropOutputWriter | 新建 `AvatarCropOutputWriter.kt`（JPEG/90 压缩 + OOM 重抛 + 独立副本回收 + 返回 Uri?），onConfirm IO lambda 改调其入口；删 unused FileOutputStream import | ✅（交叉核对 6/6 PASS + BUILD SUCCESSFUL） |
| 阶段 39 Bitmap 解码字节上限 | 新建 `BoundedInputStream.kt`（read/read/skip 累计计数超 32MB 抛 IOException）；SafeImageLoader/LauncherScanBridge 4 处 decodeStream 包装；LauncherCoverBridge decodeFile 前置长度预检 | ✅（交叉核对 6/6 PASS + BUILD SUCCESSFUL） |
| 阶段 40 空 catch 注释补齐 | OverlayTranslationService 10 处 `catch (_: Exception) {}` 补齐 §8:313 紧邻忽略理由注释（MediaProjection/ImageReader/VirtualDisplay/Image 清理、removeView/updateViewLayout 尽力而为） | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL） |
| 阶段 41 网关 catch 兜底注释 | GameWorkspaceGateway 3 处带兜底返回值 catch（safeArchiveName/fullContentPreview/safeProviderName）补 §8:314 失败兜底注释 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 42 引擎名资源化 | GameMetadataFormatter `engineText(context, engine)` 改用既有三语 `game_engine_*` 资源（与引擎选择器单一来源一致化）；删无 Context 死重载 | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL） |
| 阶段 43 LauncherDialogFactory 拆分 | 776→232 行：拆 `LauncherDialogParts`/`Confirm`/`Choice`/`Loading`/`Update` 5 个 internal 子 object，主对象保留 24 个 @JvmStatic 委托 + 常量 + fun interface，公共 API 零变更 | ✅（交叉核对 6/6 PASS + BUILD SUCCESSFUL） |
| 阶段 44 LauncherTheme 拆分 | 665→228 行：拆 `Colors`(10)/`Drawables`(18)/`Views`(16)/`Switch`/`Spinner`/`Parts`(14 helper) 6 个 internal 子 object，主对象保留 53 个 @JvmStatic 委托，公共 API 零变更（含 1 处 Parts→color() 等价改写消除分层环） | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 45 AvatarCropActivity 拆分 | 573→188 行：内类 CropView（357 行）迁 Kotlin `AvatarCropView.kt` + 解码抽 `AvatarBitmapDecoder.kt`；删 unused TAG + 12 import；行为等价（渲染/手势/裁剪/解码逐字保留） | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 46 LauncherHomeFragment 拆分 | 643→443 行：头像抽 `LauncherAvatarController.kt`（launcher 注册 + 渲染/持久化，§8 协调类模式）；最近列表抽 `LauncherRecentListRenderer.kt`（纯函数渲染，扩展点值/回调参数化）；15 个 protected open 子类契约零变更 | ✅（交叉核对 6/6 PASS + BUILD SUCCESSFUL） |
| 阶段 47 LocalAgentCallback 命名类 | LocalAgentActivity send() 内 80 行匿名 `LocalAgentRuntime.Callback` 抽为非静态私有内类 `LocalAgentCallback`（9 个 override 逐字迁移），行为等价 | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL） |
| 阶段 48 LibraryPagingHelper | LauncherLibraryFragment 1138→987 行：分页/卡片高度组（showNextPage/showPreviousPage/renderState/scheduleLoadUntilViewportFilled/拖拽加载/双卡片高度，~150 行）抽 `LibraryPagingHelper.kt`（§8 协调类 + internal 访问器）；删死代码 renderPagedGrid；子类契约零变更 | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 49 LibrarySwipeGesture | LauncherLibraryFragment 987→860 行：横滑手势组（setupSwipeGesture/分类切换/分类栏滚动+淡入动画 + swipeConsumed 守卫，~130 行）抽 `LibrarySwipeGesture.kt`（§8 协调类）；子类契约零变更 | ✅（交叉核对 6/6 PASS + BUILD SUCCESSFUL） |
| 阶段 50 LibraryToolbarUi | LauncherLibraryFragment 860→699 行（拆分完成）：工具栏/分类组（搜索/同步/折叠、设置菜单、海报样式切换、分类 chips、图标着色，~160 行）抽 `LibraryToolbarUi.kt`（§8 协调类 + 10 internal 访问器）；categoriesCollapsed 初始化经访问器保留 HD 子类语义；删 12 unused import | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 51 companion 委托收窄 | LauncherActivity 633→625：全仓调用方评估（38 个 @JvmStatic 委托中 36 个有调用方保留，含 3 个 engine 反射依赖）；删 2 个零调用方委托（applyCustomSplashImage/getNavigationOverlayBottomPadding，底层实现仍直用） | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 52 审查问题修复 | 修复阶段 38-51 审查发现的 E1 + W1-W8：AvatarBitmapDecoder 流泄漏改 `.use` 关闭；删 4 死 import；onBatchSyncComplete 补 !isAdded 守卫；AvatarBitmapDecoder/AvatarCropOutputWriter catch 收窄 IOException+SecurityException（Kotlin 两独立子句）；SafeImageLoader 显式 OOM 重抛；AvatarCropView §3 画布基色注释；删 LibraryPagingHelper 死重载；LauncherThemeSwitch 混合基色注释；showConfirm 5 参签名回退 Unit（阶段 43 误改的二进制不兼容） | ✅（交叉核对 10/10 PASS + BUILD SUCCESSFUL） |
| 阶段 53 NIT 清理 | 审查未处理 NIT 7 项：LauncherDialogChoice 删 4 处冗余 `val index = i` + 消除 3 处 `!!`（choices/depthValues/labels 用 if-null 包裹智能转换）；LibraryToolbarUi initialBinding 单行化 + showLibrarySettingsMenu 改 private；AvatarCropView 删死字段 baseScale（原 Java 同写后无读）；AvatarCropOutputWriter 改 internal；BoundedInputStream 补 available() 覆盖 + 注释修正（与 LauncherCoverBridge 20MB 独立限额）+ mark/reset 安全说明；LauncherDialogFactory 删 KDoc 用 import 改反引号；LauncherThemeSwitch mutedGray 核验合规不改 | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 54 GamePasswordDialog 迁 Kotlin | GamePasswordDialog.java（306）→ Kotlin object（3.4 首例）：嵌套 `fun interface OnPasswordSetListener`（JVM 名不变）；@JvmStatic 保留 showSetDialog/showVerifyDialog/hash；局部函数 + 闭包 var 替代 Java 数组装箱；showVerifyDialog 参数可空化（JVM 签名不变）；Java 调用方 GamePasswordLock.kt 零改动 | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL） |
| 阶段 55 LauncherAppPickerDialog 迁 Kotlin | LauncherAppPickerDialog.java（216）→ internal object：`fun interface Callback`（JVM 名不变）；show 加 @JvmStatic（Java 方法引用调用点兼容）；Item data class + Adapter 嵌套类；排序 compareToIgnoreCase 用 Kotlin compareTo(ignoreCase=true) 精确等价；LauncherAddGameActivity:479/LauncherGameEditActivity:193 零改动 | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL） |
| 阶段 56 ScanDirectoryController 迁 Kotlin | ScanDirectoryController.java（323）→ Kotlin class（3.4 第 3 例，保留实例构造 5 参）：嵌套 `fun interface OnScanRequestedListener`（JVM 名不变，方法引用 xp3TargetResolver::executeScan 兼容）；public 方法集合全保留；split 去尾空串用 `dropLastWhile { it.isEmpty() }` 复刻 Java split 语义；`coerceIn`/`part?.trim() == "1"` 等价改写；constants 入 companion；Java 调用方 LauncherManageFragment 零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 57 GameSessionController 迁 Kotlin | GameSessionController.java（244）→ Kotlin class（3.4 第 4 例）：嵌套 `Listener` 普通接口（双方法）+ `fun interface LaunchListener`（JVM 名不变，PinnedGameShortcut trailing lambda SAM 兼容）；自重启心跳 Runnable 用 `object : Runnable` 保持 this 指向自身；resolveLaunchTypeForRecord 入 companion + @JvmStatic；竞态说明/静默失败注释全保留；交叉核对发现 2 警告点（startServerPlaySession 参数 `Game?` 保留 null 防御 + 删冗余安全调用）已修；6 个 Java/Kotlin 调用方零改动 | ✅（交叉核对 3.5/4 PASS + 警告修复后 BUILD SUCCESSFUL） |
| 阶段 58 LauncherLaunchTargetPicker 迁 Kotlin | LauncherLaunchTargetPicker.java（162）→ internal object（3.4 第 5 例）：嵌套 `fun interface Callback`（JVM 名不变，Java lambda/方法引用调用点兼容）；show 加 @JvmStatic；boolean[] 装箱用局部函数 + 闭包 var 消除；collectTargets 递归前缀保留原始大小写 name（防路径小写化）；listFiles() null 防御按 documentfile 1.1.0 @NonNull 契约删除（编译证据排除 @Nullable）；4 处 catch 保留 Log 输出；LauncherAddGameActivity:298/LauncherGameEditActivity:194 零改动；assembleDebug 全量验证 dex 链路 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + assembleDebug PASS） |
| 阶段 59 ExternalImportController 迁 Kotlin | ExternalImportController.java（222）→ Kotlin class（3.4 第 6 例）：嵌套 `private fun interface ParseTask`（@Throws(Exception::class) 保留 throws 语义，JVM 名不变）；host getter/setter 属性化（appContext/mainQueue/isImportInProgress/isUiAvailable）；catch(Error) OOM 重抛注释保留 ×2 + catch(Exception) Log.e 不静默；`e.message ?: getString(unknown_error)`；trailing lambda 化 showMessageActionChoices/showConfirmDialog；games/result 参数可空保留防御；唯一调用方 LauncherManageFragment 零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 60 GameListController 迁 Kotlin | GameListController.java（299）→ Kotlin class（3.4 第 7 例）：**实证 Kotlin 属性不支持 Java 风格访问器调用**（setDataLoaded()/getVisibleGames()/isDataLoaded() 编译失败），故全部状态用私有 backing field + 显式 Java 风格函数（isLoading()/setDataLoaded()/getVisibleGames() 等）；`@Volatile disposed` + 注释保留；catch(Error) rethrow ×2 + catch(Exception) Log.w 保留；Listener 10 方法普通 interface（函数签名不变，KDoc 全保留）；`g != null` 恒真检查删除（ArrayList<Game> 非空元素类型）；**3 个 Kotlin 调用方「属性读→方法调用」语法适配**（LauncherLibraryFragment 4 行 + PadManageFragment 20 行 + LibraryPagingHelper 8 行，纯机械等价转换） | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 61 Xp3TargetResolver 迁 Kotlin | Xp3TargetResolver.java（190）→ Kotlin class（3.4 第 8 例）：executeScan JVM 签名 (List,int,boolean) void 保持（LauncherManageFragment :136 方法引用绑定 ScanDirectoryController.OnScanRequestedListener fun interface 兼容）；ScanBatchResult.results → toMutableList() 处理 removeAt(index) 语义；ScanResult @JvmField title/launchTarget/xp3Candidates 属性直访；dismissScanLoadingDialog 用局部变量规避 private var smart-cast；无 catch 块（原 Java 亦无）；唯一调用方 LauncherManageFragment 零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 62 LocalBackupController 迁 Kotlin | LocalBackupController.java（122）→ Kotlin class（3.4 第 9 例）：Java multi-catch（ActivityNotFoundException\|IllegalStateException\|IllegalArgumentException\|SecurityException）拆 4 独立 catch + 抽 `showExportError` 共享体（不可收窄为 catch(Exception) 避免扩大捕获）；try-with-resources → `openOutputStream(uri) ?: throw IOException` + `out.use{}`（先判 null 后写入）；optJSONArray 三处空检查缓存局部变量 `?.length() ?: 0`；catch(Error) rethrow 保留（原无注释不加）；LauncherManageFragment 5 调用点零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 63 DiagnosticsController 迁 Kotlin | DiagnosticsController.java（86）→ Kotlin class（3.4 第 10 例）：方法引用 `this::showDiagnosticsOptions` → trailing lambda；showMessageActionChoices ChoiceListener fun interface + when 分派；catch(Exception) Log.e + Toast 保留；Toast.makeText Int 重载无歧义；LauncherManageFragment :124/:192 零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 64 SyncSettingsController 迁 Kotlin | SyncSettingsController.java（97）→ Kotlin class（3.4 第 11 例）：嵌套 `interface BackupActions`（3 方法普通接口，Java 匿名类 LauncherManageFragment:125-133 兼容）；方法引用 `backupActions::openSyncCenter` → trailing lambda；LauncherSyncBridge.Callback 4 方法 object 匿名实现（onProgress 空体合规）；DateFormat.format 原样；LauncherManageFragment :125/:190 零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 65 GameSyncController 迁 Kotlin | GameSyncController.java（242）→ Kotlin class（3.4 第 12 例）：嵌套 `Listener`（4 方法）+ `DialogFactory`（3 方法，createSyncLoadingDialog 可空签名匹配两个 Kotlin 实现 override）；LauncherMetadataBridge.Callback 普通接口用 object 实现（非 fun interface 不可 SAM）；进度更新 lambda 用局部 val 规避 private var smart-cast + `as? TextView`；coverUrl 非空化删除 null 检查（VnMetadata.coverUrl 非空 String，语义等价）；Game.title var 属性用局部变量保持 trim 语义；LauncherLibraryFragment/PadManageFragment 零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 66 BaseGameCardAdapter 迁 Kotlin | BaseGameCardAdapter.java（257）→ Kotlin abstract class（3.4 第 13 例）：**`fun interface CardLayoutSpec`（7 个接口迁 fun interface 的最后一个，SAM 兼容两个子类：PadManageGameAdapter `::applyPadLayout` + LauncherGameAdapter lambda `{b,h->...}`）**；`OnGameCardListener` 参数声明 Game?（复刻 Java 平台类型契约，两个 Fragment 匿名 object 零改动）；inner Holder + 顶层 private helper + companion @JvmStatic compactText（PadManageGameAdapter:36 简单名→限定名 1 行必需改动）；LauncherCoverLoader.Callback 普通接口 object 实现；posterTitle 非空化删不可达 null 分支；审查补 OnGameCardListener KDoc（§8:333）；子类/调用方其余零改动 | ✅（交叉核对 3/4 PASS + KDoc 修复后 BUILD SUCCESSFUL） |
| 阶段 67 引擎三件套迁 Kotlin | 2026-08-02 批次（3.4 第 14-16 例）：EngineOption.java（22）→ `internal class` + **@JvmField ×3**（Java 直接字段访问 opt.engine/.label/.rpgMakerSubtype 兼容）+ toString=label；EnginePackageResolver.java（85）→ `internal object` + @JvmStatic ×5（11 分支 defaultPackage/forDetection/forOption/subtypeForOption/findOption 别名去横线匹配）；EngineOptionCatalog.java（41）→ `internal object` + @JvmStatic create（16 base 条目 + UNKNOWN 追加，System.arraycopy → `base + arrayOf`）；LauncherAddGameActivity/LauncherGameEditActivity 全部静态调用 + 字段访问零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 68 GameCategoryBuilder 迁 Kotlin | GameCategoryBuilder.java（155）→ `object` + @JvmStatic ×3（3.4 第 17 例）：6 const val 分类常量；build 用 TreeMap(CASE_INSENSITIVE_ORDER) + `?: 0` 累加 + cats 追加顺序保持；matches 用 isNullOrEmpty smart-cast + `developers?.get ?: emptyList()`；`game == null` 循环检查删除（List<Game> 非空元素类型）；死代码 rebuildCategories/sortGamesByTitle 按 KDoc 说明不保留；4 个 Kotlin 调用方零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 69 ExternalImportPreviewDialog 迁 Kotlin | ExternalImportPreviewDialog.java（241）→ `internal object` + @JvmStatic show（3.4 第 18 例）：嵌套 `Callback` 普通接口（双方法，ExternalImportController:144 匿名 object 零改动）；stream→`games.count { it.exists }`；weightSum/LayoutParams weight 转 Float；CheckBox isChecked/isEnabled/isClickable 属性语法；lunaBoxSessions/vniteTimers/playedTimeMap else-if 链用 isNullOrEmpty（局部 val 规避 smart-cast）；宽度兜底注释保留 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 70 AgentConfigDialog 迁 Kotlin | AgentConfigDialog.java（218）→ `internal object` + @JvmStatic ×2（3.4 第 19 例，2026-08-02 批次收尾）：multi-catch 拆 NumberFormatException/GeneralSecurityException/IllegalArgumentException 三独立子句 + 抽 `showSaveError` 承载 DevLogger.w+Toast（未合并 catch(Exception)）；text 助手 size 保持 Int + `setTextSize(size.toFloat())`；valueOf 空安全 `text?.toString()?.trim() ?: ""`；config.temperature 等字段类型核实；LocalAgentActivity:352/:370 静态调用 + 方法引用零改动；SOFT_INPUT_ADJUST_RESIZE deprecation 为忠实迁移存量提示 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 71 AgentLlmConfigDialog 迁 Kotlin | AgentLlmConfigDialog.java（250）→ Kotlin class（3.4 第 20 例，3.4 批次收官）：@JvmOverloads 构造兼容 Java 调用；LlmConfigCallback 普通接口 object 实现；split 用 `dropLastWhile { it.isEmpty() }` 复刻 Java 语义；multi-catch 拆 URISyntaxException/NumberFormatException 独立子句；postDelayed IME 显示逻辑；LauncherAiChatActivity:118 零改动 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL） |
| 阶段 72 3.4 审查问题全修复 | com_apps_3.4_batch_review.md 1 BLOCKING + 1 WARNING + 9 存量 WARNING + 12 INFO 全部修复（用户要求全修）：B-1 Math.round 语义校准；W-1 删 !!；W1 EnginePackages 扩展 8 常量；W2 ScanRootKeys 单源 4 模块统一；W3/W4 DiagnosticsController IO 下沉；W5 SyncSettingsController 守卫；W6 GameSyncController importInProgress 防重；W7 GameSessionController IO 下沉；W8 Xp3TargetResolver 异常兜底；W9 AgentLlmConfigDialog 取色统一；INFO 12 项除 I-9/I-12 合理保留全部优化 | ✅（交叉核对 13/13 PASS + BUILD SUCCESSFUL） |
| 阶段 73 审查新发现收尾 | com_apps_3.4_batch_review.md 十一节 2 项新发现 + 3 项 INFO 收尾：新发现-1 ScanRootKeys 末尾补换行；新发现-2 HandheldLaunchers/ExternalGodotPluginStrategy 4 常量改引 EnginePackages 单源（private const val 无法委托改 private val）；INFO-1 GameSyncController syncInProgress 复位提前守卫前；INFO-2 findServerPlaySessionId 主线程 IO 登记存量债务；INFO-3 ScanRootKeys @JvmField val 统一改 const val（Java 直访兼容已验证）；备注 launcherbridge 30+ 裸字面量更深存量后续立项 | ✅（交叉核对 PASS + BUILD SUCCESSFUL） |
| 阶段 74 生命周期清理技术债 | 4.1 两项生命周期清理债务清偿：LibraryToolbarUi 补 cleanup()（searchDebounce removeCallbacks）+ LibrarySwipeGesture 补 cleanup()（OnItemTouchListener 提取字段 + removeOnItemTouchListener + 3 View setOnTouchListener(null) + detector 置空）+ LauncherLibraryFragment.onDestroyView 补 toolbarUi/swipeGesture cleanup（super 与 _binding=null 之前）；OverlayTranslationService 三处 postDelayed（projectionRestart/projectionInit/translationRetry）改字段持有 Runnable + onDestroy removeCallbacks 置空 + 删除仅 synchronized 内访问的 @Volatile latestImage（§8:308） | ✅（交叉核对 8/8 PASS + BUILD SUCCESSFUL） |
| 阶段 75 LauncherCustomVndbSearchDialog 迁 Kotlin | LauncherCustomVndbSearchDialog.java（375）→ `internal object` + @JvmStatic show（3.3 项剩余唯一手拼弹窗 + 9.1 语言约束，3.4 批次第 21 例）：7 处 ContextCompat.getColor 统一 LauncherTheme.text/textMuted/inputHint；selectedSource 局部 var + sourceChips arrayOfNulls 复刻数组装箱；setTextSize Int→Float（Kotlin 无单参 Int 重载）+ setLineSpacing dp 转 toFloat；两处窗口 flag 多行 `|` 改单行 `or`（行首运算符分号插入）；dialogWidthPx(288) 宽度兜底保留；LauncherTheme 新增 inputHint()（4.5 登记缺失，Colors 走 LauncherThemeParts.color）；EditPlayTimeDialog 2 处 + LocalAgentActivity:102 取色统一；LauncherLibraryFragment:513/PadManageFragment:547/PadGameFragment.java:557 零改动 | ✅（交叉核对 9/9 PASS + BUILD SUCCESSFUL） |
| 阶段 76 KEY_PROFILE_AVATAR 单源化 | 4.4 登记清偿：com.core.CorePreferences 新增 KEY_PROFILE_AVATAR="profile_avatar" 主源；SyncManager 删 companion private const（原 :402）改引 CorePreferences（:222/:315）；LauncherUserData MAIN_PREF_KEYS 字面量改引；LauncherAvatarPersistence const val 保留兼容副本 + KDoc 注明主源（Java 编译期常量引用 PadGameFragment.java:696 零改动）；全仓 "profile_avatar" 字面量收敛为 2 处（主源 + 兼容副本） | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 77 引擎 scoped 键单源化 | §9.12 引擎键单源：新建 com/core/launcher/EngineSaveKeys.kt（4 const val：KR/ARTEMIS/TYRANO scoped_save_dir + TYRANO external_network）；统一 7 调用方——LauncherKrkrBridge 删 4 private const + 8 引用、SyncManager 删 4 private const + prefs 键 8 处改引（JSON 协议字段名 settings.put/has/optBoolean 保持字面量不动）、KrkrLauncher:149、ScriptEngineLaunchers:225、EngineSaveLocations:54/74、ArtemisLauncher:257、LauncherUserData MAIN_PREF_KEYS 4 项；engine 模块 TyranoActivity:650 保留镜像（模块边界豁免，§4.4 既有口径） | ✅（交叉核对 6/6 PASS + BUILD SUCCESSFUL） |
| 阶段 78 LiquidGlass 色值注释 + 登记校准 | 4.1 登记 LiquidGlass 硬编码色补 §3 内容特效注释（LauncherLiquidGlassNavigation:136/213 两处行注释覆盖 3 个 Color(0xFF...) 色值：surface 0xFF171919 / muted 0xFFB6BFBB / 浅色 0xFF63716B，注明内容特效豁免不随 LauncherTheme 切换）；4.5 过时登记清理——AgentLlmConfigDialog/LocalAgentActivity ContextCompat.getColor 取色条已随阶段 72/75 整改，标记 ✅ | ✅（交叉核对 PASS + BUILD SUCCESSFUL） |
| 阶段 79 MAIN_PREF_KEYS 剩余 6 键单源化 | 4.4 评估推进：新建 com/core/prefs/LauncherMainKeys.kt（6 镜像 const，注明 com.apps 主源，§8 分层）；LauncherUserData MAIN_PREF_KEYS 6 项改引；OverlayTranslationService:91 launcher_theme_style 改引（消除 com.core 内部重复）；LauncherAccountFragment:158 + LauncherProfileFragment:432 auth_saved_email 改引（该键无 com.apps 主源，主源定于 com.core 镜像）；全仓 6 键字面量收敛为镜像 + com.apps 主源定义两处；交叉核对发现文档「17 偏好键」计数实为 16 项，顺带校准 | ✅（交叉核对 7/7 PASS + BUILD SUCCESSFUL） |
| 阶段 80 引擎包名字面量清零 | 4.4 引擎包名范围外字面量收尾：LauncherAddGameActivity:294 + LauncherGameEditActivity:429 默认 GAMEHUB emulator 值 "com.xiaoji.egggame" 改引 EnginePackages.EXTERNAL_GAMEHUB（加 import）；ExternalGameLaunchers:483 isGameHubPackage 首值改引（同包直引，egggamz 拼写变体保持字面量）；全仓 "com.xiaoji.egggame" 裸字面量清零（仅剩 EnginePackages 单源定义） | ✅（交叉核对 PASS + BUILD SUCCESSFUL） |
| 阶段 81 styleSwitch 死代码删除 | 4.1 技术债清偿：LauncherTheme.kt:178-179 styleSwitch() 方法（零业务调用方，agent.md:183 明确禁止）+ LauncherThemeSwitch.kt:13-37 styleSwitch() 实现删除；未使用的 ColorStateList 导入清理；4.1 对应条目标记 ✅ | ✅（交叉核对 PASS + BUILD SUCCESSFUL） |
| 阶段 82 engine 模块 yukihub_prefs 单源化 | 4.1 技术债清偿：engine 为独立模块（app 依赖 engine，engine 不得反向依赖 app），新建 engine/src/main/java/com/core/engine/EnginePrefs.kt（object，const val APP_PREFS = "yukihub_prefs"，KDoc 注明主源在 app 模块 LauncherPreferences）；TyranoActivity.kt:649 原 PREFS_NAME 常量删除、两处 getSharedPreferences 改引 EnginePrefs.APP_PREFS；ArtemisLauncherBaseActivity.java:10 原 PREFS_NAME 常量删除、getSharedPreferences 改引 EnginePrefs.APP_PREFS；engine 模块 "yukihub_prefs" 字面量收敛为 EnginePrefs 唯一单源；4.1 对应条目标记 ✅ | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL） |
| 阶段 83 McpServerStore catch(Throwable) 收窄 | 4.1 技术债清偿：McpServerStore.decode() 逐条记录解析 `catch (ignored: Throwable)` → `catch (ignored: IllegalArgumentException)`（§8:313 只捕获可预期具体异常；validateName/validateEndpoint 对外只抛 IllegalArgumentException，validateEndpoint 内部 URI 异常已包装重抛；紧邻注释说明跳过迁移损坏记录的安全忽略意图）；agent/store 目录下 `ignored: Throwable` 残留清零；4.1 对应条目标记 ✅ | ✅（交叉核对 3/3 PASS + BUILD SUCCESSFUL） |
| 阶段 84 审查 W1-W4 修复 | 交叉核对审查清理：W-1 LibraryToolbarUi.kt cleanup()/onTextChanged 的 `searchDebounce!!` 改 `?.let`（smart-cast 失效避免强制解包）；W-2 engine 侧 KEY_TYRANO_EXTERNAL_NETWORK 单源闭环——EnginePrefs.kt 新增镜像常量（KDoc 注明主源 app EngineSaveKeys），TyranoActivity companion 原私有副本删除、改引 EnginePrefs.KEY_TYRANO_EXTERNAL_NETWORK，engine 模块该裸字面量清零；W-3 确认 com.xiaoji.egggamz 为真实历史包名（AndroidManifest/快捷桥/帮助文案均引用）非死值——EnginePackages 新增 EXTERNAL_GAMEHUB_LEGACY，ExternalGameLaunchers.isGameHubPackage + LauncherGameHubShortcutBridge shell 命令改引常量；W-4 EnginePrefs.kt 末尾补换行；INFO 项 LauncherMainKeys.kt KDoc 精确化（KEY_AUTH_SAVED_EMAIL 无 app 侧主源，声明本常量为该键主源）；后续审查再补 onTextChanged 剩余两处 searchDebounce!! 改 ?.let + LauncherGameHubShortcutBridge Shizuku.newProcess 反射 KDoc 标注 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 85 EdgeToEdge 豁免注释补齐 | 4.7 项 2 登记收尾：4 处 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS 未走 helper 的站点补豁免注释——HdModeActivity.kt / PadGameModeActivity.java / PadSettingsActivity.java 三处 configureLandscapeWindow（系统栏着色为 LauncherTheme.bg + 刘海短边裁切 + 关闭对比度增强，与 helper 透明状态栏语义不同）+ ResourceStationActivity.configureImmersiveStatusBar（透明状态栏 + 底栏色导航 + 固定 LIGHT 标志）；注释统一标注「豁免，见 agent.md §8 grep 监控与重构计划 4.7 项 2」；LocalAgentActivity 此前已有豁免注释，全 app 模块 helper 外 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS 5 处全部具备豁免注释 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 86 com.apps W1-W6 修复 | 多域审计 WARNING 收尾：W1 LauncherAppPickerDialog onCreateViewHolder 手写 density 计算改 LauncherTheme.dp（68f*scale/7f，保留单次 half-up 舍入）；W2 LauncherRepository.textContext catch(RuntimeException) 补「可安全忽略」紧邻注释（顺带 GameMetadataFormatter:82 补注释）；W3/W4 LauncherActivity + HdModeActivity scheduleAutoUpdateCheck 的 postDelayed 返回值存入 autoUpdateDelay 字段并在 onDestroy dispose（对齐 splashDelay 模式）；W5 LauncherMotion.runAfterPulse lambda 首行补 isAttachedToWindow 守卫（§8:302）；W6 HdSaveManagerFragment loadCategories runOnUiThread 守卫改 `!isAdded \|\| view == null` 规范形式 | ✅（交叉核对 6/6 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 87 com.core 空 catch 注释补齐 | 多域审计 WARNING A 收尾（§8:313 紧邻忽略注释）：com.core 21 处空 catch 补「可安全忽略」注释——LauncherUserData:373/378（重启降级）、ExternalGodotPluginStrategy:208（流关闭）、VnMetadata:56（序列化）、PotatoVnImporter:216/257 + VniteImporter:211 + LunaBoxImporter:362/373（日期解析逐格式尝试）、GameSaveFileManager:165（临时目录清理）、YukiDatabaseHelper 9 处（幂等索引/迁移回填/事务 endTransaction/safeAlter）、HttpClient:131/148（日期解析/响应体关闭）；全 com.core 空 catch（catch 体仅 `{}`）清零 | ✅（交叉核对 11/11 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 88 com.core 引擎包名字面量改引 | 多域审计 WARNING B 收尾（§9.12 单源）：com.core 引擎包名裸字面量改引 EnginePackages——ExternalGameLaunchers PspStrategy/CitraStrategy/EdenStrategy（INTERNAL_PSP/EXTERNAL_PPSSPP/INTERNAL_CITRA/EXTERNAL_AZAHAR/EXTERNAL_EDEN）、LauncherScanBridge emulatorPackageForEngine（PSP/3DS/SWITCH/RPGMAKER/GODOT 分支）、LauncherGameLaunchBridge 9 处（psp/ppsspp/azahar/eden）、LauncherModuleBridge isGodotPluginPackage（INTERNAL_GODOT，godot3/godot4 无常量保留字面量）；全 com.core 上述包名裸字面量收敛为 EnginePackages 唯一单源 | ✅（交叉核对 9/9 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 89 engine 模块 catch(Throwable) 注释补齐 | 引擎侧异常处理规范收尾（§8 边界兜底）：com.core.tyrano（TyranoActivity canonical 解析 2 处 + ensureWritableSaveDirectory、AsarArchive 流关闭 2 处 + parseLong + logInfo、TyranoStorage insideRoot、TyranoLocalHttpServer 5 处）+ com.akira.tyranoemu.remote（KirikiroidLauncherBaseActivity 反射兜底 4 处）+ bridge（NativeBridge 3 处 + KrPathUtils 1 处）共 20 处空 catch/静默吞补中文注释；vendored 第三方引擎源码（org.tvp.kirikiri2 / org.libsdl.app / org.cocos2dx.lib / com.yuri.onscripter / com.ies_net.artemis）为上游导入代码有意不改（豁免，见 4.5） | ✅（交叉核对 11/11 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 90 GameRepository 拆 PlaySessionRepository | 3.5 com.core 大文件拆分首例（§8:323 Repository 按职责切片）：新建 com/core/data/PlaySessionRepository.kt（344 行，16 个游玩会话/时长方法 + PlayActivity data class + ensureSingleChangedRow + normalizePlayStatus 迁为同包顶层 internal 单源），GameRepository 保留薄委托层（1039→763 行）；共享同一 YukiDatabaseHelper 实例保证跨 games/play_sessions 事务原子性；导出导入/统计组（exportPlaySessionsJson/importGamesJson/importPlaySessionsJson/recalculatePlayStats）与 CRUD 私有 helper 深度耦合按评估暂缓保留；全仓库调用方（LauncherGameLaunchBridge/LauncherRepositoryBridge/SyncManager + 桥下游）零变更 | ✅（交叉核对 6/6 PASS（含 1 项口径说明：GameRepository 保留的导出导入/统计方法仍含 play_sessions SQL 属设计内）+ BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 91 LauncherUserData 拆 LauncherPlayRecords | 3.5 com.core 大文件拆分第二例（§8:323 按职责切片）：新建 com/core/userdata/LauncherPlayRecords.kt（258 行，游玩记录缓冲 appendPlayRecord/readPlayRecords/clearPlayRecords/removePlayRecords + 服务端会话映射 rememberServerPlaySession/findServerPlaySessionId/removeServerPlaySession + 文件/锁常量 + 4 私有 helper），LauncherUserData 保留同签名 @JvmStatic 委托层（690→520 行）；readText 提为 internal 供新对象复用（writeText/getUserDataDir 本就 public）；getRealtimePlaytimeDeviceId 保留原类（设备 ID 偏好非记录组）；全仓库调用方（GameSessionController 服务端映射 3 处 + 委托层）零变更；交叉核对发现并修复 LauncherUserData 残留未使用 JSONArray import | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 92 GameSaveFileManager 按职责切片拆分 | 3.5 com.core 大文件拆分第三例（§8:323/325 按职责切片）：新建 com/core/data/SaveFileUtils.kt（168 行，internal object 纯文件/目录校验与复制原语 + BUFFER_SIZE/MAX_SAVE_ZIP_BYTES/MAX_SAVE_ZIP_FILES）、SaveZipTransfer.kt（139 行，internal class ZIP 打包/解压：exportToZip/createTemporaryImportDirectory/extractZipToDirectory）、SaveDocumentTransfer.kt（95 行，internal class SAF DocumentFile 递归复制）；GameSaveFileManager 保留存档位置解析/路径记录/公开 API 编排（623→294 行），gameKey/isBuiltInPackage 留 companion；全仓库调用方（LauncherSaveManagerActivity/LauncherSaveGameListActivity）零变更；importInternalSaveFromZip 临时目录创建→解压→清理顺序与 finally 安全忽略注释逐字保留 | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 93 AgentToolRegistry 按职责切片拆分 | 3.5 com.core 大文件拆分第四例（§8:323/325 按职责切片）：新建 com/core/agent/runtime/AgentToolSchemas.java（154 行，包私有 final class：definitions() 20 工具 Schema 目录 + 5 个 schema 构建 helper）、AgentToolArgumentValidator.java（262 行，包私有 final class：validateArguments() 逐工具校验 + 9 个校验 helper）；AgentToolRegistry 保留执行分派/审批编排/谓词/游戏库查询/结果格式化（733→361 行），definitions() 与 validateArguments() 改薄委托，MAX_RESULT_CHARS 留原类；删不再使用的 AgentRelativePath/HashSet/Iterator/Set import；调用方 LocalAgentRuntime 与 AgentToolRegistryTest（经委托）零变更 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + AgentToolRegistryTest 通过 + git diff --check 通过） |
| 阶段 94 OverlayTranslationService 按职责切片拆分 | 3.5 com.core 大文件拆分第五例（§8:323/325 按职责切片）：新建 com/core/translation/TranslationCapture.kt（296 行，internal class MediaProjection 截屏捕获链路：init/ensureProjection/startContinuousCapture/teardownCapture/takeLatestJpegBytes/imageToJpegBytes/stop + projectionReady/hasActiveProjection）、TranslationOverlayUi.kt（297 行，internal class 悬浮按钮/加载与结果卡片 UI：showFloatingButton/setupButtonTouchListener/showLoadingCard/showResultCard/overlayWindowType/dp，构造注入 context/windowManager/mainHandler + 点击/长按回调，不持有 Service 实例）；OverlayTranslationService 保留 Service 类名/companion projectionData、projectionResultCode/生命周期/前台通知/翻译编排/关闭确认（702→202 行）；删不再使用的 TAG 常量与捕获/UI import；调用方 AndroidManifest 声明与 TranslationSettingActivity 零变更；线程模型（captureHandler 回调/runOnSingle 取帧/主线程 UI）不变 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 95 W1/W2 修复 | 审查 WARNING 收尾：W-1 KDoc 断链 2 处（LauncherRepositoryBridge:110/140 裸文本 GameRepository.PlayActivity → PlaySessionRepository.PlayActivity，对齐阶段 90 类型迁移）；W-2 TranslationCapture 2 处 `!!` 消除（init 局部变量 thread 代替 captureThread!!.looper、takeLatestJpegBytes 局部捕获 val frame 代替 image!!），agent.md §8:317 全文件 `!!` 清零 | ✅（交叉核对 3/3 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 96 LocalAgentRuntime 按职责切片拆分 | 3.5 com.core 大文件拆分第六例（§8:323/325 按职责切片）：新建 com/core/agent/runtime/AgentToolInvocation.java（323 行，包私有 final class：逐工具审批+执行+审计管线 process() + awaitApproval 双重载 + safeToolError/auditResult + ToolRoundState 嵌套状态类）；LocalAgentRuntime 保留模型轮次循环/会话控制/错误收尾/上下文管理（647→388 行），ensureActive/post 提为包私有 static 供新类引用，run() 两处上下文压缩审批改调 AgentToolInvocation.awaitApproval（消除重复）；共享状态（pendingRows/successfulMutationTools/successfulMcpRegistration/sideEffectsCommitted/remoteMcpEffectUncertain）迁入 ToolRoundState，sessionWorkspaceGrants/scanRootsGranted 迁入新类实例字段；MCP 客户端跟踪经 Consumer 回调回 LocalAgentRuntime.setActiveMcpClient；两处 continue→return 行为等价；删 AgentSnapshotStore 等 12 个不再使用 import；公开 API 与 LocalAgentActivity/LocalAgentRunTokenTest 调用方零变更 | ✅（交叉核对 10/10 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 97 SyncManager 拆 SyncSnapshotCodec | 3.5 com.core 大文件拆分第七例（§8:328 跨类共享逻辑提取）：新建 com/core/sync/SyncSnapshotCodec.kt（76 行，public object：MAX_REMOTE_SNAPSHOT_BYTES 16MB/MAX_LOCAL_BACKUP_BYTES 32MB + snapshotToText/compressGzip/decompressIfGzip 纯静态编解码）；SyncManager companion 删 3 方法 2 常量，sync()/本地备份方法 10 处调用改引 SyncSnapshotCodec（512→455 行，<500）；LauncherSyncBridge 3 处引用更新（:18/:86/:102）+ 新增 import；删 4 个不再使用 stream/zip import；公开 API 与 RESOLVE_* 常量零变更 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 98 ExternalGameLaunchers 拆 WinlatorLauncher | 3.5 com.core 大文件拆分第八例（§8:323/325 按职责切片）：新建 com/core/launcher/WinlatorLauncher.kt（193 行，internal object：Winlator 系启动协议 isWinlatorPackage/isWinlatorTarget/launch/launchWinlator/addWinlatorExtras/resolveWinlatorExecPath/extractDesktopExecutable/parseWinlatorContainerId/resolveDesktopPath）；ExternalGameLaunchers WinlatorStrategy 改委托、删除已迁移方法、保留 3 个 @JvmStatic 委托（504→353 行，<500），ExternalGameLaunchersTest 经委托零变更；删 java.io.File（迁移后不再使用）与 android.content.pm.PackageManager（本就未使用）import；交叉核对另发现并修复 LauncherScanBridge:351 KDoc 断链（ExternalGameLaunchers.isWinlatorPackage → WinlatorLauncher.isWinlatorPackage）；EmulatorLauncher 调用方零变更 | ✅（交叉核对 5/5 PASS + BUILD SUCCESSFUL + ExternalGameLaunchersTest 通过 + git diff --check 通过） |
| 阶段 99 3.5 收尾（9.12 关闭 + 9.9 评估） | 3.5 剩余项收尾：① 9.12 文档漂移修正——MAIN_PREF_KEYS（16 键）单源化实际已于阶段 76/77/79 完成（数组零裸字面量，全引 LauncherMainKeys/ScanRootKeys/CorePreferences/EngineSaveKeys 单源），3.5 列表由「待办」改「✅」并指向 4.4；② 9.9 LocalActivityManager 迁移正式评估（登记 4.5）：6 HD Fragment 各自 LocalActivityManager + 生命周期转发 + 任意 Activity 嵌入 detailContainer + HdEmbeddedActivityOwner 代理 Activity Result + HdPageMotion 动画 + 4 种不同继承基类，属大型协调迁移暂缓，给出分阶段建议（先抽共享宿主基类 → 逐 Fragment 迁子 Fragment → 清代理路径） | 📌 评估完成（文档） |
| 阶段 100 阶段 13 方案 A 落地（onTrimMemory） | 阶段 13 进程回收缓解方案 A 实施：LauncherActivity 新增 onTrimMemory——UI_HIDDEN(20)/RUNNING_LOW(10)..RUNNING_CRITICAL(15)/BACKGROUND(40)+ 时释放图片内存缓存（阈值经交叉核验按 SDK 实际字节码修正，排除前台轻度 RUNNING_MODERATE=5）；新增 releaseMemoryCaches() 调用 SafeImageLoader.clearMemoryCache()/LauncherCoverLoader.clearMemoryCache()/PinnedGameShortcut.clearIconCache()；三个缓存类各增 @JvmStatic evictAll 清空方法（仅丢引用不 recycle，惰性重建，KDoc 注明方案 A 背景）；缓解内置引擎游戏同 UID 整包回收、降低 LMK 优先回收概率 | ✅（交叉核对 5/6 PASS（A1 阈值修正后）+ BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 101 4.3 更新源 URL 核实 | 4.3 待确认项关闭（实证，无代码改动）：经 GitHub API 核实 `FALLBACK_RELEASE_URL` 的 tag `test` 为仓库唯一真实发布（prerelease 测试版先行 demo-0.9.9.9.9，含全部 APK 资源），非占位符；LauncherUpdateBridge 按标签名解析版本号与本地比较、FALLBACK 指向同一标签页，当前显式标签形式正确保持现状，若后续出正式版频道再迁 /releases/latest | ✅（实证核实，见 4.3） |
| 阶段 102 复核 WARNING 修复 | 分域复核（4.7 校准先例）发现 3 项 WARNING 修复：① SyncSnapshotCodec 收窄 throw Exception→IllegalArgumentException（快照超限）/IOException（解压放大防护），@Throws 同步收窄；② WinlatorLauncher resolveWinlatorExecPath `workingPath!!` → 局部 val wp（§8:317 清零）；③ LauncherPlayRecords 5 处宽泛 catch 补紧邻忽略理由注释（可重建临时缓冲/非关键路径语义）；行为逐字等价，调用方 catch(Exception) 兼容 | ✅（交叉核对 PASS 2/3，C-2 仅为 git status 含阶段 95-101 未提交存量 + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 103 sync 域 throw Exception 收窄 | §8:315 合规（分域复核观察项落地）：SyncManager 4 处 + LauncherSyncBridge 12 处 `throw Exception(...)` 收窄——context null「上下文不可用」5 处→IllegalStateException、参数/格式校验 4 处（备份为空/无效/文件不可用）→IllegalArgumentException、网络/文件/流/限制 7 处→IOException；SyncSnapshotCodec.compressGzip @Throws(Exception)→@Throws(IOException)；@Throws(Exception) 上界注解保留仍准确；调用方（LauncherSyncCenterActivity/LocalBackupController/SyncManager.sync catch(Exception)）零兼容影响 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 104 importer 域 throw Exception 收窄 | §8:315 合规收尾：PotatoVnImporter 4 处 + VniteImporter 2 处 + PlayniteImporter 1 处 + LunaBoxImporter 3 处 `throw Exception(...)` 收窄——I/O/流/目录操作失败 7 处（无法创建目录/打开 ZIP/读取文件）→IOException、数据文件缺失 3 处（未找到 galgames.json/gameDocs.json/games.csv）→IllegalArgumentException；Vnite/Playnite 补 import IOException；调用方 ExternalImportController（catch(Exception)）零兼容影响 | ✅（交叉核对 4/4 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 105 9.9 第一步：HD 嵌入宿主收拢 | 9.9 分阶段建议①落地（§8:327 准备性收敛）：新建 `HdEmbeddedActivityHost.kt`（组合宿主，非继承——4 种不同继承基类无法插中间基类，阶段 99 评估），收拢 6 个 HD Fragment（HdAccount/HdHome/HdProfile/HdManage/HdSaveManager/HdSettings）的 LocalActivityManager 创建/生命周期转发/嵌入销毁；宿主三方法契约 start(id,intent):View? / beginClose(child):String? / destroy(id)；6 Fragment 改 embeddedHost 字段 + host 生命周期转发 + showEmbeddedActivity/closeEmbeddedActivity 改 host 调用；HdHome 保留 super 回退 + hideContainer=true 变体；embeddedActivityId 赋值时机（先赋后 startActivity，失败污染行为）与原实现逐字对齐；新增防御性守卫仅在原 NPE 不可达路径生效；全仓库 `import android.app.LocalActivityManager` 收敛为宿主唯一一处；对外契约（HdEmbeddedActivityOwner/HdModeActivity/HdPageMotion）零变更 | ✅（交叉核对 15/15 PASS + BUILD SUCCESSFUL + git diff --check 通过） |
| 阶段 106 W-1 空白判定语义校准 | 多域审计 W-1（§2:40 trim 语义）：WinlatorLauncher.kt 3 处 `isNullOrBlank()` → Java `trim()` 语义形式（仅移除 <= U+0020）——:100 `execPath != null && execPath.trim().isNotEmpty()`、:126 `wp != null && wp.trim().isNotEmpty()`、:173 `rootPath == null || rootPath.trim().isEmpty()`；修复处补同款中文注释（核验发现第 3 处缺注释已补）；原 pre-阶段98 存量偏差随拆分时机修正；ExternalGameLaunchers 其余 4 处 isNullOrBlank（非 Winlator 路径）按渐进迁移口径留存量（见 4.4） | ✅（交叉核对 6/6 PASS + 注释缺口补后 BUILD SUCCESSFUL + git diff --check 通过） |

> 每个 ✅ 阶段均经交叉核对 agent 全 PASS + 强制重编译 BUILD SUCCESSFUL（详细记录见 git 历史，文档不再展开）。

---

## 三、剩余工作计划

### 3.1 大文件拆分（5.1 剩余，>500 行）

| 文件 | 行数（2026-08-03 实测） | 拆分方案 |
|------|------|----------|
| game/LauncherLibraryFragment.kt | ~~1138~~ → 699 | ✅（阶段 48/49/50）拆 `LibraryPagingHelper` + `LibrarySwipeGesture` + `LibraryToolbarUi` 三 helper（~520 行），子类契约零变更 |
| PadUi/PadSettingsActivity.java | ~866 | 按 Section 拆 Controller（阶段 52 评估：applyTheme 为跨全部 section 的全局样式遍历（触及 krkr/ons/tyrano/account 控件并回调各 section 渲染），selectSection 依赖 currentSection 状态 + 跨 section 调用，section 边界不干净，硬拆需大量 package-private 暴露 + ThemeController 反向依赖各 section，属 §8「为拆分而拆分」，暂缓，见 4.5） |
| theme/LauncherDialogFactory.kt | ~~776~~ → 232 | ✅（阶段 43）拆 Parts/Confirm/Choice/Loading/Update 子 object |
| PadUi/PadManageFragment.kt | 755 | 拆 SearchCategory / SyncDelegate / GameActions（阶段 47 评估：三 listener 块已是薄门面、真实逻辑在 GameListController/GameSyncController，且 categories/selectedCategory 等共享状态跨组纠缠，硬拆需大量 internal 暴露面，暂缓，见 4.5） |
| profile/LauncherProfileFragment.java | 695 | 抽 ProfileRankFetcher + ProfileImageSync（阶段 51 评估：rank 组渲染与 Java protected binding/私有方法深度耦合，image 组含用户已暂缓的 3.2 项 6 copyImageToInternal，机械抽取需大量参数化改造，暂缓，见 4.5） |
| home/LauncherHomeFragment.kt | ~~642~~ → 443 | ✅（阶段 46）抽 LauncherAvatarController.kt + LauncherRecentListRenderer.kt（纯函数渲染器参数化扩展点） |
| theme/LauncherTheme.kt | ~~665~~ → 228 | ✅（阶段 44）拆 Colors/Drawables/Views/Switch/Spinner/Parts 子 object |
| LauncherActivity.kt | ~~633~~ → 625 | ✅（阶段 51）companion 委托层评估收窄：38 委托中删 2 个零调用方（applyCustomSplashImage/getNavigationOverlayBottomPadding），其余 36 保留（含 3 个 engine 反射依赖） |
| theme/LauncherParticleView.java | 603 | 按 style 拆 ParticleStyleStrategy（阶段 51 评估：单一职责渲染器，7 样式分支共享 particle 状态/paint/主题色，符合 §8:325「单一渲染器可保留」；agent.md §3 粒子规范需保持内聚，暂缓，见 4.5） |
| widget/AvatarCropActivity.java | ~~573~~ → 188 | ✅（阶段 45）抽 AvatarCropView.kt + AvatarBitmapDecoder.kt（内类 CropView 迁 Kotlin） |
| agent/LocalAgentActivity.java | ~~~548~~ → 549 | ✅（阶段 47）抽 LocalAgentCallback 命名内类 |
| game/LauncherAddGameActivity.java / LauncherGameEditActivity.java | 500/507 | 图片 IO 下沉（随 5.2） |

> com.core 侧另有 9 个 >500 行文件（§8:324 同等适用），见 3.5。

### 3.2 UI 层文件 IO 下沉（5.2 剩余）

| 项 | 内容 | 说明 |
|----|------|------|
| 项 2 | AvatarCropActivity onConfirm → 抽 `AvatarCropOutputWriter`（IO 线程 + OOM 重抛 + 回收判断下沉） | ✅（阶段 38） |
| 项 6 | LauncherProfileFragment copyImageToInternal → LauncherImageBridge.copyToInternal | 封面路径；两副本语义差异大，暂缓（用户决策保留现状） |

### 3.3 弹窗共享基件（4.6 + 4.4 补充）

- ✅ LauncherCustomVndbSearchDialog 迁 Kotlin + 取色统一（阶段 75）：3.3 项三处手拼弹窗中 AgentConfigDialog/ExternalImportPreviewDialog 已随 9.1 迁移，本项最后剩余手拼弹窗已迁，宽度兜底（dialogWidthPx）已确认保留
- `LauncherDialogParts` 共享构建器：原 AgentConfigDialog / LauncherCustomVndbSearchDialog / ExternalImportPreviewDialog 三处手拼重复已消（两处随迁移重构、一处已确认宽度兜底）；如后续仍有重复可评估抽公共 builder
- 4.4 AvatarCropActivity `activity_avatar_crop.xml` + ViewBinding 抽取（当前仅取色已统一）

### 3.4 语言约束 + 接口迁移（9.1/9.2）

- ✅ **3.4 批次已全部完成**（阶段 54-71，共 20 个 Java 文件迁 Kotlin + 7 个单方法接口迁 fun interface）：详见「二、已完成任务总览」阶段 54-71 登记

### 3.5 其他

- � **9.9**：`LocalActivityManager`（HD 6 Fragment：HdAccount/HdSaveManager/HdHome/HdProfile/HdManage/HdSettings）迁子 Fragment 或 NavComponent——已评估（阶段 99，见 4.5）；分阶段建议①已落地（阶段 105，组合宿主 `HdEmbeddedActivityHost` 收拢创建/生命周期/销毁，见已完成任务总览），②逐 Fragment 迁子 Fragment、③清 HdEmbeddedActivityOwner 代理路径待续
- ✅ **9.12**：`LauncherUserData.MAIN_PREF_KEYS`（16 偏好键）单源化评估完成（阶段 76/77/79，见 4.4）：数组零裸字面量，全部引用 LauncherMainKeys/ScanRootKeys/CorePreferences/EngineSaveKeys 单源；LauncherMainKeys 为 core 侧镜像（注明主源）
- **com.core 大文件拆分评估（§8:324 同等适用）**：~~GameRepository.kt 1039~~（✅ 阶段 90 拆 PlaySessionRepository 344 行，现 763 行）、~~LauncherUserData.kt 690~~（✅ 阶段 91 拆 LauncherPlayRecords 258 行，现 520 行）、~~GameSaveFileManager.kt 623~~（✅ 阶段 92 拆 SaveFileUtils/SaveZipTransfer/SaveDocumentTransfer，现 294 行）、~~AgentToolRegistry.java 733~~（✅ 阶段 93 拆 AgentToolSchemas/AgentToolArgumentValidator，现 361 行）、~~OverlayTranslationService.kt 676~~（✅ 阶段 94 拆 TranslationCapture/TranslationOverlayUi，现 202 行）、~~LocalAgentRuntime.java 647~~（✅ 阶段 96 拆 AgentToolInvocation，现 388 行）、~~SyncManager.kt 517~~（✅ 阶段 97 拆 SyncSnapshotCodec，现 455 行）、~~ExternalGameLaunchers.kt 504~~（✅ 阶段 98 拆 WinlatorLauncher，现 353 行）、GameWorkspaceGateway.java 963（已评估暂缓，见 4.5）
- **阶段 13**：进程回收缓解方案 A（onTrimMemory 释放内存）/ B（onResume 快速恢复现场）/ C（excludeFromRecents）待用户决策

---

## 四、注意事项与遗留事项

### 4.1 技术债（不阻塞）

| 项 | 说明 |
|----|------|
| OverlayTranslationService 悬浮窗归属（9.5） | com.core Service 持有 WindowManager + 悬浮 View（addView 3 处），归属界定未定 |
| ✅ McpServerStore.kt:168 | `catch (ignored: Throwable)` 已登记债务——阶段 83 已收窄为 `catch (ignored: IllegalArgumentException)` |
| ✅ engine 模块 2 处 `yukihub_prefs` | ArtemisLauncherBaseActivity.java:10、TyranoActivity.kt:649（独立模块另论）——阶段 82 已单源化至 engine 侧 EnginePrefs.APP_PREFS |
| ResourceStationActivity 返回箭头 | `<` 文本（已资源化 `settings_back_arrow`）兜底，项目无返回箭头 drawable |
| OverlayTranslationService:674 本地 dp() 副本 | com.core 侧（§1/§7），计划 com.apps 清零声明不含 core 侧 |
| LauncherLiquidGlassNavigation:136/212/213 | 硬编码 `Color(0xFF...)` 色值无说明注释（§3；若属内容特效应补注释登记）——✅ 阶段 78 已补 §3 内容特效注释（surface/muted 色） |
| ✅ LauncherTheme styleSwitch 零调用方（阶段 52 登记） | LauncherTheme.kt:175 全仓仅 facade 自委托 1 行、无业务调用方；与 §4「禁止继续使用 styleSwitch()」冲突，但为公共 API 删除需决策（签名兼容优先 vs 重复实现优先删除）——阶段 81 已删除死代码 |
| WinlatorLauncher 反向依赖 launchPackage（阶段 106 审计 W-2） | WinlatorLauncher.kt:46 反向调用 ExternalGameLaunchers.launchPackage，同包 object 间循环依赖（§8:330）；后续抽 launchPackage 到独立工具 object 打破环 |
| HdEmbeddedActivityHost 使用废弃 LocalActivityManager（阶段 105 审计 W-3） | 阶段 105 将 6 Fragment 的 LocalActivityManager 收敛到 HdEmbeddedActivityHost 单点（6→1，存量收敛非新增使用，KDoc 标注「阶段 105 准备性收敛」）；废弃 API 使用点已最小化，后续按 4.5 阶段 99 评估的 ② 逐 Fragment 迁子 Fragment / ③ 清 HdEmbeddedActivityOwner 代理路径 推进 |

### 4.2 已决策事项

| 项 | 决策 |
|----|------|
| LauncherNavRenderer half-up（阶段 22 W1） | 接受：非整数 density 才有 ≤1px 差异，整数密度零差异 |
| UrlOpener 不带 CATEGORY_BROWSABLE（阶段 22 W2） | 接受：AOSP matchCategories 语义下拦截器不会掉出 chooser |
| LauncherAccountSettingsActivity 中文「用户不存在」（阶段 16 W2） | 仅保留错误码（服务端保证 USER_NOT_FOUND） |
| LauncherAiChatActivity characterName（阶段 16 W6） | 统一为「AI 聊天」 |
| 封面路径 copyImageToInternal（阶段 34） | 保留现状，不强行统一（缺 fd.sync/commit 仅影响封面） |
| GameActionMenuFactory 跨上下文宽度（14.3） | 默许合规：统一 LauncherDialogFactory.dialogWidthPx 兜底，不按上下文路由；豁免条款见 agent.md §6 |
| LauncherUpdateFormatter 未加 @JvmStatic | 当前仅 Kotlin 调用方，非必须；后续有 Java 调用方再补 |

### 4.3 待决策 / 待确认

- ✅ 阶段 13 进程回收缓解方案 A（onTrimMemory 释放内存）已决策并实施（阶段 100，见已完成任务总览）；B/C 未选
- ✅ `LauncherUpdateFormatter.FALLBACK_RELEASE_URL` 含 "test" 标签——已核实（阶段 101）：经 GitHub API 实证仓库 Weiss-UltimateSavior/RinneMobile 唯一发布即 tag `test`（prerelease「测试版先行demo-0.9.9.9.9」，含 app-release-0.9.9.9.9.apk 等资源），非占位符；LauncherUpdateBridge 按该标签解析版本号（0.9.9.9.9）与本地版本比较，FALLBACK 指向同一标签页。当前显式标签形式正确，保持现状；若后续发布正式版频道再改 /releases/latest
- 阶段 15 警告 3：确认服务端只返回 `USER_NOT_FOUND` 错误码——客户端侧已核实（LauncherAccountSettingsActivity:241-244 `isUnchangedPlayDataError` 仅按错误码匹配，无中文分支，符合阶段 16 W2 决策；:220 注释注明服务端对未变化游玩数据以该码返回）；**服务端实际返回需服务端侧确认**（本端无法验证，待用户/服务端提供）

### 4.4 跨模块遗留（com.core 侧，分层约束不可引用 com.apps）

- ✅ `LauncherUserData.MAIN_PREF_KEYS`（16 偏好键，原登记 17 实为 16）单源化评估完成（阶段 76/77/79）：KEY_PROFILE_AVATAR + 引擎 scoped 4 键 + 剩余 6 键（LauncherMainKeys 镜像）全部收敛至单源，MAIN_PREF_KEYS 数组零裸字面量
- ✅ `SyncManager.KEY_PROFILE_AVATAR`（com/core/sync/SyncManager.kt:401，companion 内 private const val）与 apps 侧 `LauncherAvatarPersistence.KEY_PROFILE_AVATAR` 公开单源两处并存；已统一下沉 com.core.CorePreferences.KEY_PROFILE_AVATAR 主源（阶段 76，LauncherAvatarPersistence 保留 const 兼容副本 + 注明主源）
- ✅ 引擎 scoped_save_dir 四键单源（阶段 77）：新建 com/core/launcher/EngineSaveKeys.kt，LauncherKrkrBridge/SyncManager/KrkrLauncher/ScriptEngineLaunchers/EngineSaveLocations/ArtemisLauncher/LauncherUserData 统一引用；SyncManager JSON 协议字段名保持字面量；engine 模块 TyranoActivity:650 保留镜像（模块边界豁免）
- ✅ 引擎包名范围外字面量（阶段 31 有意保留）：com/apps 侧已清零（阶段 72 EnginePackageResolver/GameSessionController + 阶段 80 LauncherAddGameActivity/LauncherGameEditActivity GAMEHUB 值，全仓 "com.xiaoji.egggame" 收敛至 EnginePackages；阶段 84 将历史包名 "com.xiaoji.egggamz" 收敛至 EnginePackages.EXTERNAL_GAMEHUB_LEGACY，ExternalGameLaunchers + LauncherGameHubShortcutBridge 改引）；engine 模块 ArtemisLauncherBaseActivity:76（"internal.artemis.compat(.v2)"，未引用 EnginePackages）保留（模块边界豁免）
- 📌 Winlator 关键词字面量（winlator/glibc/proot/mobox/winalator，WinlatorLauncher.isWinlatorPackage:20 谓词列表）未下沉 EnginePackages（阶段 106 审计 I-1 存量）：属包名关键词判定而非引擎包名全名，暂缓；若后续评估可并入 EnginePackages 谓词

### 4.5 已知偏差与观察项

- PadManageFragment 拆分评估（阶段 47）：三块 listener（ActionMenuCallbacks/GameSyncController.Listener/GameListController.Listener）已是薄门面，真实逻辑在 GameListController/GameSyncController；categories/gameDevelopers/selectedCategory/searchQuery 等共享状态跨组纠缠，拆三 delegate 需大量 internal 暴露面，收益有限，暂缓（若后续做，应优先只抽 showMoreOptionsDialog 的动作选项构建或保持现状）
- LauncherProfileFragment 拆分评估（阶段 51）：rank 组（refreshProfileRankFromServer/refreshPlayTimeRank/refreshWeeklyPlaytimeChart）渲染与 Java protected binding/私有方法深度耦合，纯参数化抽取需大量改造；image 组含用户已暂缓的 3.2 项 6（copyImageToInternal→LauncherImageBridge）。暂缓（若后续做，可只抽 refreshWeeklyPlaytimeChart 的纯数据计算部分）
- LauncherParticleView 拆分评估（阶段 51）：单一职责粒子渲染器，7 样式 update/draw 分支共享 particle 数组/paint/主题色缓存，符合 §8:325「单一渲染器可保留」；agent.md §3 粒子样式规范要求内聚实现。暂缓
- PadSettingsActivity 拆分评估（阶段 52）：applyTheme 是跨全部 section 的全局样式遍历（styleSidebarItem 5 个 sidebar + 10+ 个 section 开关 + formInputs + inline action + 各 section 渲染回调），selectSection/currentSection 状态跨 section 驱动；按 Section 拆 Controller 会导致 ThemeController 反向依赖 Krkr/Account/Metadata 渲染，package-private 暴露面大。暂缓
- HD 6 Fragment LocalActivityManager 迁移评估（9.9，阶段 99）：HdHome/HdSaveManager/HdAccount/HdProfile/HdManage/HdSettings 各持 `LocalActivityManager` + 生命周期转发（dispatchResume/Pause/Stop/Destroy）+ 经 `startActivity(id, intent)` 把**任意**子 Activity 嵌入各自 detailContainer + `HdEmbeddedActivityOwner` 代理（Splash 图片选择器/MediaProjection 授权/通知权限，因嵌入 Activity 无法收 Activity Result）+ HdPageMotion 进出场动画；迁移需为每个被嵌入 Activity（ResourceStationActivity 等）提供 Fragment 等价物并重接全部 Result 代理路径，且 4 种不同继承基类（LauncherHome/Account/Profile/Manage + 裸 Fragment）无法共用中间基类。规模属大型协调迁移，非单阶段可完成；分阶段推进：① 抽共享嵌入宿主收拢生命周期转发去重——✅ 已完成（阶段 105，组合宿主 `HdEmbeddedActivityHost.kt`，非继承；6 Fragment 的 LocalActivityManager 创建/生命周期转发/嵌入销毁收敛到单点，对外契约零变更）→ ② 逐 Fragment 迁子 Fragment（每个一阶段）→ ③ 清 HdEmbeddedActivityOwner 代理路径。②/③ 待续
- `GameSessionController.resolveLaunchTypeForRecord` 对 `com.yuki.yukihub.ons/tyrano` 历史包名判定为 "external"，而 Kotlin resolveLaunchType 判定为内部引擎（:230-231 已有注释说明有意不用共享谓词，保持记录格式）
- `GameActionMenuFactory` 2 处 `launcher_input_hint_color` 因 LauncherTheme 无对应方法保留 `ContextCompat.getColor`（建议后续增 inputHint）——✅ 阶段 75 已新增 LauncherTheme.inputHint() 并统一 EditPlayTimeDialog 2 处
- HD/Pad nav 主题 Logo 白色 tint 为混合用途（已注释说明）
- 阶段 15 警告 1/2/4：com.core 4 处 KDoc 含 `yukihub_prefs` 字样（文档注释保留）；GamePasswordDialog 异常消息改英文仅影响 LogCat；新增资源未补 en/ja 翻译（回退默认值，与原硬编码一致）
- ✅ vendored 第三方引擎空 catch 豁免（阶段 89）：engine 模块 org.tvp.kirikiri2 / org.libsdl.app / org.cocos2dx.lib / com.yuri.onscripter / com.ies_net.artemis 为上游导入的引擎源码（约 30 处 `catch (Throwable ignored) {}`），属引擎内部稳定性的有意模式，不在 §8:313 注释要求范围，本轮有意不改；项目自有引擎代码（com.core.tyrano / com.akira.tyranoemu.remote / bridge）已全部补齐
- GameWorkspaceGateway 拆分评估（阶段 90）：963 行纯静态工具类（private 构造 + 全 static），公开 API（list/readText/diff/search/write 等 18 个）共享 cross-cutting 私有 helper（encoding 探测 ~130 行 / JSON Pointer ~70 行 / diff ~80 行 / 安全校验 rejectSensitive/rejectVisualControls/checkActive）；硬拆需把整组共享 helper 下沉为多个 internal 工具 object（参照 LauncherTheme Parts 先例），属可行但收益低于职责切片（无独立生命周期/状态边界），暂缓（若后续做，可只抽 EncodingHelpers + JsonHelpers 两个高内聚 helper object）
- ✅ `AgentLlmConfigDialog:170/171/244 + LocalAgentActivity:102` 直接 `ContextCompat.getColor` 取色（§3）：已随阶段 72（AgentLlmConfigDialog W9 取色统一）+ 阶段 75（LocalAgentActivity:102 → LauncherTheme.textMuted）整改完毕
- 📌 阶段 106 审计 INFO 存量观察（不阻塞）：I-1 Winlator 关键词字面量下沉 EnginePackages 见 4.4；I-2 HdHomeFragment `container.post { refreshNavigationChrome() }` lambda 缺 isFinishing/isDestroyed 守卫（存量，阶段 105 仅重命名 hostActivity，post 内 refreshNavigationChrome 为幂等 UI 刷新，风险低）；I-3 HdSettingsFragment onViewCreated 先 super 后初始化 host 的顺序差异为历史既定（阶段 105 交叉核验确认保留）；I-4 AgentToolInvocation.auditResult `catch(JSONException ignored)` 缺 §8:313 紧邻注释（阶段 96 存量迁移，后续补）；I-5 导入器存量 `catch(ignored: Exception)`（SimpleDateFormat/toInt 兜底，非本批次，阶段 104 未触碰）；另 ExternalGameLaunchers 4 处 isNullOrBlank（:92/:185/:232/:334，非 Winlator 路径）+ launcher 域其余存量（ScriptEngineLaunchers 4/KrkrLauncher 4/ArtemisLauncher 3/HandheldLaunchers 3/EngineSaveLocations 1/ExternalGodotPluginStrategy 1/ExternalRenPyPluginStrategy 1 + WinlatorLauncher takeIf(String::isNotBlank)/takeIf(String::isNotEmpty)）按渐进迁移口径留存量，随拆分时机修正

### 4.6 需实机确认

- AgentLlmConfigDialog 平板端：宽度经 `LauncherTabletPortraitScaler` 缩放但树内 padding/字号未走全树缩放，平板竖屏视觉待确认
- AgentLlmConfigDialog:137 `fetchLlmConfig.onError` 仅 `isShowing()` 守卫（风险低）
- LauncherLaunchTargetPicker loading 不可取消（`cancelable=false` 工厂契约），长扫描 UX 待确认
- 阶段 42 引擎名显示值统一（ONS→ONScripter、3DS→Nintendo 3DS、Switch→Nintendo Switch (Eden)、Unknown→本地化）游戏详情弹窗展示文字有变，各语言跑一遍
- 阶段 45 AvatarCropView Java→Kotlin 迁移（渲染/触摸/裁剪）实机验证：拖拽、双指缩放、裁剪框对齐、确定输出，竖屏手机各跑一遍
- 各阶段「视觉无感」类改动（dp 舍入差异、边距变化、主题色统一）在竖屏手机 / 平板横屏 / 平板竖屏 / 深浅色模式各跑一遍

### 4.7 2026-08-03 多 agent 分域复核校准记录

> 5 个只读 search agent 分域扫描 com.apps + com.core 全目录，对照 agent.md §1-§8 与本文档声明逐项验证。结论：核心声明（分层、单源化、dp/catch/守卫/弹窗/文案清理、HD/Pad 隔离）总体属实；修正以下失实并登记新发现。

**失实修正**：
1. catch(Throwable) 剩余计数：原「agent 包 4 处」→ 实际 **14 处**（agent 包 13：AgentMutationTransaction:19/25、AgentSnapshotStore:66、AgentPrivateWorkspace:141/172、GameWorkspaceGateway:520/523/527、AgentScanRootGateway:216、LocalAgentRuntime:414/465、McpHttpClient:60 + 债务 McpServerStore:168；diagnostics 1：GameDiagnostics:75），均带 §8 注释
2. EdgeToEdge：原「22 Activity 内联完成」→ 另有 4 处 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` 未走 helper 未登记：HdModeActivity:132、PadGameModeActivity:72、PadSettingsActivity:403、ResourceStationActivity:71（系统栏着色模式，需 helper 或豁免注释）——✅ 阶段 85 已全部补豁免注释（含 LocalAgentActivity 既有豁免，5 处全覆盖）
3. 阶段 20/21「dp() 清零」口径：§7 grep 仍命中 3 个豁免位置（LauncherTabletPortraitScaler:36 缩放器、ManageHost:49 接口契约、LauncherManageFragment:240 接口实现），属 §1 允许的缩放包装，非本地密度副本
4. 3.1 行数刷新（LauncherAddGameActivity 500、LauncherProfileFragment 695、LauncherHomeFragment 642、LauncherTheme 665、LauncherActivity 633、AvatarCropActivity 573 等）
5. GameWorkspaceGateway 行号漂移（677/788/848 → 680-681/790-791/850-851）

**新发现已登记**：4.1（AgentConfigDialog 宽度/hint、OverlayTranslationService 空 catch 与 dp 副本、Bitmap 字节上限、LiquidGlass 硬编码色）、4.4（SyncManager 常量并存、引擎包名范围外残留）、4.5（LauncherHomeFragment Color.WHITE、ContextCompat.getColor 存量、agent.md §6 漂移）

**确认合规**：com.core 分层 import com.apps 0 命中；yukihub_prefs/kr_engine_version 单源化 0 代码残留；nav 取色 setColorFilter(Color.GRAY) 清零；HD/Pad 无 LauncherDialogFactory.show 错用；LocalActivityManager 6 Fragment 仍在（3.5 剩余属实）；runOnUiThread/回主线程守卫全合规；手拼弹窗清零；硬编码颜色/文案清零（除登记项）；ArtemisLauncher.stopSaveSync / OverlayTranslationService.captureThread.quitSafely 释放入口存在；com/core/ui 目录已删。

---

## 五、执行原则

1. **渐进式**：每阶段独立可交付；可按域并行
2. **存量优先**：Java 存量文件「在原文件内继续修改」，只在相关功能改动时迁移 Kotlin（§2）
3. **零行为变更**：拆分/迁移保持对外 API 签名不变，`@JvmStatic`/`@JvmField`/`const val` 兼容
4. **验证基线**：分层 grep（`com.core` 不得 import `com.apps`）+ 强制重编译 + `git diff --check`
5. **不强制重写**：禁止为满足格式要求进行无业务价值的大规模重写（§8）

---

## 六、合规亮点（保持不破坏）

- §8 分层架构：`com.core`→`com.apps` 反向依赖清零；`LauncherUiBridge` 桥接 + `com.core.agent.{net,runtime,store,workspace}` + `com.core.userdata` 包结构清晰
- §8 异常处理：catch(Throwable) 全量收窄，仅 agent 包事务/进程边界保留并带 §8 注释；新增 catch 块均走 DevLogger.w
- §8 互操作：`@JvmStatic`/`@JvmName`/`@JvmField` 注解完整，Java 调用方兼容
- §5 弹窗工厂：手工弹窗清零，宽度统一 `LauncherDialogFactory.dialogWidthPx` 兜底
- §6 Pad/HD 弹窗区分 + HD 缩放器隔离（HdFragment `usePortraitScaler()=false`）
- 安全防护（WebView 加固/路径穿越防护/AndroidKeyStore 加密/API key 脱敏/LauncherUrlOpener scheme 白名单）
- 协程结构化（无 GlobalScope/正确重抛 CancellationException）
- ViewBinding 生命周期（onDestroyView 解除 listener 并置空 binding）
- 三语资源机制（strings.xml + strings_core_ui.xml + strings_social.xml）
