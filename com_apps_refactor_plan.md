# `com.apps` 架构治理与规范对齐计划

> 基线：`app/src/main/java/com/apps/agent.md`（§1–§8）+ `project_memory.md` 硬约束
> 来源：4 agent 并行扫描 `com.apps` 全目录当前状态
> 生成日期：2026-08-01｜最近校准：2026-08-02（Phase 0/1/2 完成后全量重扫，commit `2d28b0d`）

---

## 一、已完成（一句话概括）

| 阶段 | 内容 | 状态 |
|------|------|------|
| **Phase 0 分层纠偏** | `com.core`→`com.apps` 反向依赖清零；agent/ + LauncherUserData + TranslationSettingActivity 迁包；`LauncherUiBridge` 桥接就位 | ✅ 已合并 |
| **Phase 1 弹窗统一** | 6+ 处手拼弹窗迁工厂；`ManageHost` 改 fun interface；抽 `AgentConfigDialog`/`ExternalImportPreviewDialog`/`LauncherUrlOpener`；catch(Throwable) 清零 8 处；`LauncherSaveGameListActivity` 4 处 runOnUiThread 加守卫 | ✅ 已合并 |
| **Phase 2 常量+工具统一** | `LauncherPreferences`/`LauncherThemeStyle` 单一来源（删 `LauncherActivity` companion 30 行兼容常量）；`LauncherEdgeToEdgeHelper` 抽取（12 处迁移）；`dp()` 11 处统一到 `LauncherTheme.dp`；`EngineOptionCatalog`/`EnginePackageResolver` 抽取（AddGame -116 行 / EditGame -87 行）；`launcher_particle_palette` 资源化；sync action + log tag 统一 | ✅ 已合并（`2d28b0d`） |

---

## 二、当前评分速览（2026-08-02 校准）

| 维度 | 评分 | 说明 |
|------|------|------|
| 整体 | **良+（88/100）** | 分层清零、弹窗工厂化、常量单一来源主路径完成；剩余集中在异常收窄、大文件、HD/Pad 一致性 |
| 根级 + home + data + widget | 中-良 | LauncherActivity companion 已清理（-30 行）；AvatarCrop/HomeFragment 内联 IO 未下沉 |
| game + profile + account | 中 | **LauncherGameActionController（558 行）未删除**，仍被 PadGameFragment 调用；catch(Throwable) 47 处（远超原估 25）；runOnUiThread 未守卫 30 处（超原估 18） |
| theme + PadUi + HDModel | 中 | 4 个 500+ 行大文件；HD 弹窗错用竖屏工厂；Color.GRAY/WHITE 硬编码；GameActionMenuFactory 裸用 dp(288) |
| settings + sync + chat + agent + translation | 中 | **13 处额外 configureEdgeToEdgeWindow 未迁移**（原计划只列 10 个）；chat 残留 2 处手拼弹窗；LauncherUrlOpener 未推广到 settings 3 处 |

---

## 三、剩余工作计划（按优先级）

### 阶段 3：异常收尾 + runOnUiThread 守卫（P1，可批量）

> 实际存量远超原计划估计，需重新扫底。

#### 3.1 catch(Throwable) 收窄（实际 47 处，非原估 25）

| 域 | 位置（主要集中） | 建议 |
|----|------|------|
| game | LauncherAddGameActivity×7、LauncherGameEditActivity×7、LauncherGameActionController×4、GameListController×2、LauncherLaunchTargetPicker×2、LocalBackupController×3、LauncherSaveGameListActivity×1 | ActivityNotFoundException/IOException/IllegalArgumentException + DevLogger.w |
| PadUi | PadSettingsActivity×2、PadManageFragment×5(catch Exception)、PadGameFragment×3(catch Exception) | SQLiteException/IllegalStateException/ActivityNotFoundException |
| 根级/home/data/widget | LauncherActivity×2、LauncherHomeFragment×2、LauncherRepository×1、LauncherCoverLoader×1、AvatarCropActivity×1 | OutOfMemoryError 传播 + Exception log |
| account/theme/HDModel/agent/sync | LauncherAccountFragment×1、LauncherTheme×1、HdSettingsFragment×1、LocalAgentActivity×2、LauncherSyncCenterActivity×2(catch Exception) | ActivityNotFoundException/RuntimeException/IOException |
| sync | LauncherSyncCenterActivity:91 `throw new Exception` | 改 `throw IOException` |

> 硬约束：silent `catch (_: Exception)` 禁止，必须 DevLogger.w 或显式 safe ignore。

#### 3.2 runOnUiThread 守卫（实际 30 处未守卫，非原估 18）

| 文件 | 未守卫行号 |
|------|------|
| LauncherGameActionController | 238/335/350/402/443（5 处，随 5.3 删除该类自然消除） |
| LauncherSaveGameListActivity | 91/182/202/226（4 处） |
| LauncherSaveManagerActivity | 68/99/123/165（4 处） |
| LauncherSyncCenterActivity | 156/181/188/213/222/235/244（7 处） |
| LauncherGameEditActivity | 215/298/309/391（4 处） |
| LauncherAddGameActivity | 270/382（2 处） |
| PadGameFragment | 430/494（2 处，Fragment 用 `!isAdded \|\| binding == null`） |
| LauncherPublicChatActivity | 243（1 处） |
| LauncherProfileFragment | 305/514（2 处） |
| LauncherAppPickerDialog | 70（1 处） |
| LauncherLaunchTargetPicker | 78（1 处） |
| LauncherModuleCompatibilityActivity | 99（1 处） |
| LauncherSaveCategoryActivity | 58（1 处） |
| HdSaveManagerFragment | 88（1 处） |
| AvatarCropActivity | 213（1 处） |
| TranslationSettingActivity | 198（1 处） |

统一加 `if (isFinishing || isDestroyed) return`（Activity）/ `if (!isAdded || binding == null) return`（Fragment）。

### 阶段 4：手写弹窗清零 + Dialog 共享基件（P2）

| 编号 | 问题 | 位置 | 方案 |
|------|------|------|------|
| 4.1 | showCustomLlmDialog 手拼 95 行 | chat/LauncherAiChatActivity:206 | 抽 `LauncherCustomLlmConfigDialog` |
| 4.2 | showMoreMenu 手拼 PopupWindow | chat/LauncherAiChatActivity:114 | 抽 `LauncherMenuSheet` |
| 4.3 | AlertDialog.Builder 手拼 | game/LauncherLaunchTargetPicker:46 | 走 `LauncherDialogFactory` |
| 4.4 | buildRoot 手拼 75 行 | widget/AvatarCropActivity:104 | 抽 `activity_avatar_crop.xml` + ViewBinding |
| 4.5 | showScanDepthChoices ●/○ 拼接 | theme/LauncherDialogFactory:519 | 改 compactChoice 选中态 |
| 4.6 | 三个专用 Dialog 手拼重复 | AgentConfigDialog / LauncherCustomVndbSearchDialog / ExternalImportPreviewDialog | 抽 `LauncherDialogParts` 共享构建器 |
| 4.7 | ExternalImportPreviewDialog 工厂化 + 宽度/取色 | game:40/52/67/156/187/198 | 走 PadDialogFactory 或共享基件 |

### 阶段 5：大文件拆分 + UI 层 IO 下沉 + 重复类删除（P2，渐进）

#### 5.1 大文件拆分（>500 行，按行数降序，2026-08-02 校准）

| 文件 | 当前行数 | 较基线变化 | 拆分方案 |
|------|------|------|----------|
| game/LauncherLibraryFragment.kt | 1139 | — | 拆 LibraryToolbarUi / LibrarySwipeGesture / LibraryPagingHelper |
| PadUi/PadGameFragment.java | 783 | +178（Phase 5.3 迁移 GameActionMenuFactory 后实现 7 个 ActionMenuCallbacks 回调） | 拆 PadGamePagingController / PadGameAvatarRenderer |
| PadUi/PadSettingsActivity.java | 866 | -1 | 按 Section 拆 Theme/Engine/Metadata/Account Controller |
| theme/LauncherDialogFactory.kt | 774 | -31（W2 提取 LauncherUpdateFormatter + §1 删 dp 副本） | 拆 Confirm/Choice/Loading/Update 子 object |
| PadUi/PadManageFragment.kt | 750 | -2（删 loadNextPage 死代码） | 拆 SearchCategory / SyncDelegate / GameActions |
| home/LauncherHomeFragment.kt | 694 | -4（删空 onPause + ACTION_VIEW 迁 LauncherUrlOpener） | 抽 LauncherAvatarController + LauncherRecentListRenderer |
| profile/LauncherProfileFragment.java | 666 | +1 | 抽 ProfileRankFetcher + ProfileImageSync |
| theme/LauncherTheme.kt | 654 | +1（catch 精确化：Throwable → Resources.NotFoundException） | 拆 Colors/Drawables/Switch/Spinner 子 object |
| LauncherActivity.kt | 644 | -2 | companion 委托层评估收窄（已 -30 行） |
| theme/LauncherParticleView.java | 607 | +3（补 setFocusable/setClickable/setFocusableInTouchMode） | 按 style 拆 ParticleStyleStrategy |
| widget/AvatarCropActivity.java | 583 | -9 | 抽 AvatarCropView + AvatarBitmapDecoder |
| agent/LocalAgentActivity.java | 548 | +3（独立 e2e 实现补注释引用 §8.1） | 抽 LocalAgentCallback 命名类 |
| game/LauncherAddGameActivity.java | 544 | +22 | 图片 IO 下沉（随 5.2） |
| game/GameActionMenuFactory.kt | 520 | — | 抽 EditPlayTimeDialog 后降至 ~300 |
| game/LauncherGameEditActivity.java | 511 | +11 | 图片 IO 下沉（随 5.2） |
| ~~game/LauncherGameActionController.java~~ | ~~558~~ | **已删除**（Phase 5.3 完成） | ✅ 整体删除 |
| ~~PadUi/PadDialogFactory.kt~~ | ~~538~~ | **已回落 469 行**（W4 拆出 PadUpdateDialog + §1 删 dp 副本） | ✅ 移出清单 |

> Phase 5.3 删除 `LauncherGameActionController.java`（-558 行）后，`PadGameFragment.java` 因实现 `ActionMenuCallbacks` 7 个回调方法净增 178 行，属预期。后续随 5.1 拆分时一并瘦身。

#### 5.2 UI 层文件 IO 下沉

| 文件 | 位置 | 方案 |
|------|------|------|
| LauncherHomeFragment | 506-568 copyAvatarToInternal | 抽 LauncherAvatarPersistence |
| AvatarCropActivity | 197-224 onConfirm | 抽 AvatarCropOutputWriter |
| LauncherAddGameActivity | 498-531 copyCoverToInternalStorage | 改用 `LauncherScanBridge.copyCoverToInternalStorage` |
| PinnedGameShortcut | 101-135 decodeShortcutBitmap | 主线程解码 ⚠️，改 IO 线程缓存 |
| LauncherProfileFragment | 620-631 isReadableImageUri | 主线程 openInputStream ⚠️，移到 AppExecutors.runOnIo |
| LauncherProfileFragment | 476-533 copyImageToInternal | 提 LauncherImageBridge.copyToInternal |

#### 5.3 重复类整体删除（✅ 已完成）

- ~~`game/LauncherGameActionController.java`（558 行）~~ **已删除**。原与 `GameActionMenuFactory.kt` 95% 重复，仅 `PadGameFragment` 一处调用。已让 `PadGameFragment` 改用 `GameActionMenuFactory.showGameActionMenu`（`ActionMenuConfig.includeEditAction=false`、`dialogWidthDp=270`），删除该类。
- 一次性消除：558 行重复代码 + 5 处 catch(Throwable) + 5 处 runOnUiThread 未守卫（原 `LauncherGameActionController` 内部实现）。
- 迁移后 `PadGameFragment` 新增 5 处 `if (!isAdded() || binding == null) return` 守卫、`openOnsGameSettings` catch 收窄为 `ActivityNotFoundException | IllegalArgumentException` + DevLogger.w。
- 子弹窗宽度对齐 §6 标准（详情/编辑时长 288dp、动作菜单/删除确认 270dp），实机确认见阶段 10.3。

### 阶段 6：HD/Pad 一致性 + 取色统一（P2）

#### 6.1 HD 弹窗工厂错用

| 位置 | 问题 | 方案 |
|------|------|------|
| HdSettingsFragment:19/147/170/181/194 | HD 横屏用竖屏 LauncherDialogFactory | 改用 PadDialogFactory |
| HdModeActivity:88 | showUpdateResultDialog 走竖屏工厂 | PadDialogFactory 增 showUpdateResult，或按 isLandscapeUiMode 派发 |

#### 6.2 HD 布局运行时拆装

- HdHomeFragment:193-218 removeView/addView 拆装竖屏布局 → 为 HD home header 设计独立 XML

#### 6.3 取色统一（ContextCompat.getColor / Color.* → LauncherTheme）

| 域 | 位置 |
|----|------|
| PadUi/HDModel | PadGameModeActivity:179/200、HdModeActivity:179/182（Color.WHITE/GRAY） |
| game | GameActionMenuFactory 10 处、ExternalImportPreviewDialog 4 处、LauncherLaunchTargetPicker 2 处、ScanDirectoryController:171、LauncherSaveGameListActivity:124 |
| 根级 | LauncherActivity:290、LauncherPendingActivity:33、AvatarCropActivity:92/101-102 |

#### 6.4 弹窗宽度兜底

- GameActionMenuFactory:161/326/471 裸用 dp(288) 无兜底 → 提 `dialogWidthPx` 共享 util

#### 6.5 装饰 View 显式禁用焦点

- LauncherParticleView:66-70 补 `setFocusable(false)` / `setClickable(false)`

### 阶段 7：硬编码文案 + 死代码 + 可读性收尾（P2-P3）

#### 7.1 硬编码文案

| 位置 | 内容 | 方案 |
|------|------|------|
| chat/LauncherAiChatActivity:82、LauncherAiChatMessageAdapter:21 | "AI"/"（AI）" | 复用 R.string.social_ai_chat |
| chat/LauncherAiChatActivity:229、agent/AgentConfigDialog:39 | "https://api.example.com/v1" hint | 抽 string resource |
| settings/ResourceStationActivity:96 | backButton.setText("<") | 改 drawable 返回图标 |
| settings/LauncherToolboxActivity:50-58 | 工具品牌名数组 | 集中 ToolboxTool enum |
| settings/LauncherKrkrSettingsActivity:242、PadSettingsActivity:234 | 引擎版本号字面量 | 移 `<string-array>` |
| game/EngineOptionCatalog | "Kirikiri"/"ONScripter"/"Tyrano" 等标签硬编码 | 走 R.string（部分已资源化，4/16 处） |
| game/ExternalImportController:57-60 | "Playnite（JSON）" 等 | 资源化 |
| game/GamePasswordDialog:301 | 中文异常消息 | 改英文或错误码 |
| account/LauncherAccountSettingsActivity:247 | message.contains("用户不存在") | 改匹配错误码 |
| sync/LauncherSyncCenterActivity:88 | "yukihub_backup_" 文件名前缀 | 抽 R.string.sync_backup_file_prefix |
| home/LauncherHomeFragment:591/638 | "Y" 头像首字母 | 抽 R.string.launcher_avatar_fallback_initial |
| theme/LauncherTheme:491 | "null" Spinner 兜底 | 改空串或 "—" |

#### 7.2 死代码清理

| 位置 | 问题 | 验证 |
|------|------|------|
| PadUi/PadManageFragment:382-384 | `loadNextPage(forceFullRefresh)` private 无调用 | ✅ 确认 dead |
| home/LauncherHomeFragment:145-146 | `onPause()` 空实现仅 super | ✅ 确认 dead |
| ~~game/GameMetadataFormatter:21-25~~ | ~~playStatusText 无 Context 重载~~ | ❌ **非死代码** — GameActionMenuFactory:300 + LauncherGameActionController:256 调用 |

#### 7.3 @Volatile 误用

- LauncherActivity:481 `launcherSplashShownInProcess`、LauncherViewModel:67 `selectedItem` → 移除 @Volatile（仅主线程访问）

#### 7.4 代码可读性

- LauncherPendingActivity:27-34、LauncherTabletPortraitScaler:34-55、LauncherLeaderboardActivity:26-29、LauncherDisclaimerActivity:14-19 → 单行压缩

#### 7.5 LauncherUrlOpener 推广

| 位置 | 现状 | 方案 |
|------|------|------|
| settings/ResourceStationActivity:228-236 | 自行 openExternalUri + 手写 host 白名单 | 改用 LauncherUrlOpener.open |
| settings/LauncherMetadataSourceActivity:121 | startActivity 无 catch | 改用 LauncherUrlOpener.open |
| settings/LauncherToolboxActivity:68 | startActivity 无 catch | 改用 LauncherUrlOpener.open |

### 阶段 8：新发现项（本轮校准新增）

#### 8.1 configureEdgeToEdgeWindow 13 处未迁移（原计划只列 10 个）

> Phase 2 迁移了原计划 10 个 Activity，但全量扫描发现 13 个额外文件仍保留旧内联实现。

| 文件 | 行号 | 备注 |
|------|------|------|
| LauncherActivity.kt | 284 | 用 `LauncherPreferences.isDarkMode`（与标准略有不同） |
| LauncherSyncCenterActivity.java | 257 | — |
| LauncherToolboxActivity.java | 94 | — |
| LauncherAppSettingsActivity.kt | 410 | — |
| LauncherKrkrSettingsActivity.java | 245 | — |
| LauncherMetadataSourceActivity.java | 136 | — |
| AvatarCropActivity.java | 86 | — |
| LauncherChatSelectActivity.java | 131 | — |
| TranslationSettingActivity.kt | 347 | — |
| LocalAgentActivity.java | 494 | 保留独立 e2e（技术理由：WindowCompat insets/cutout/对比度 flags 与 bindInsets()/IME 处理耦合，见 LocalAgentActivity.java:493-519 注释） |
| LauncherThemeMenuActivity.java | 210 | — |
| LauncherPublicChatActivity.java | 330 | **特殊**：用 `ColorUtils.calculateLuminance(LauncherTheme.primary(this)) > 0.5d` 决定 LIGHT_STATUS_BAR |
| LauncherAiChatActivity.java | 471 | **特殊**：同上 luminance 逻辑 |

**方案**：标准 11 处直接替换为 `LauncherEdgeToEdgeHelper.apply(this)`；2 处 luminance 逻辑需扩展 helper（增 `apply(activity, adjustResize, useLuminance)` 重载）或保留独立实现并注释说明。

#### 8.2 LauncherParticleView local dp(float)

- LauncherParticleView:386 `private float dp(float value)` 返回 float，非标准 int dp()，不在原计划 2.7 列表内。评估是否统一到 `LauncherTheme.dp` 的 float 重载（已存在 `dp(context, value: Float): Int`，但签名不同）。

#### 8.3 com.core 跨模块 "yukihub_prefs" 13 处（技术债，非阻塞）

> `com.core` 内 13 处硬编码 `"yukihub_prefs"` 字面量，与 `com.apps.LauncherPreferences.APP_PREFS` 重复。涉及 ArtemisLauncher/KrkrLauncher/EngineSaveLocations/ScriptEngineLaunchers/UiScaleUtil/DevLogger/OverlayTranslationService/AgentScanRootGateway/SyncManager/LauncherUserData/LauncherAuthBridge/LauncherBridgeSupport/LauncherKrkrBridge。

**方案**：在 `com.core` 内定义 `CorePreferences.APP_PREFS` 镜像常量（注明主源在 `LauncherPreferences`），或下沉到 `com.core` 公共常量 object，`com.apps.LauncherPreferences` 反向引用。

#### 8.4 catch(Throwable) / runOnUiThread 实际存量远超估计

- catch(Throwable)：实际 **47 处**（原计划估计 ~25）
- runOnUiThread 未守卫：实际 **30 处**（原计划估计 ~18）
- 阶段 3 工作量需相应上调，建议分批处理（game 域优先，随 5.3 删 LauncherGameActionController 自然消除 5+5 处）

### 阶段 9：agent.md 校对新增问题清单（2026-08-02 深度扫描）

> 本轮用 4 agent 深度扫描 `com.apps` + `com.core` 全目录，对照 agent.md 校对后新增的规范缺口与违规存量。规范条款已沉淀到 agent.md §1–§8，本节收录具体问题位置供分批治理。

#### 9.1 语言约束违规：近期新建 Java 文件（16 个）

| 创建批次 | 文件 | 应迁为 |
|---|---|---|
| 2026-07-26 | DiagnosticsController、ExternalImportController、GameSessionController、ScanDirectoryController、SyncSettingsController、Xp3TargetResolver、GameListController、GameCategoryBuilder、GameSyncController、LocalBackupController、ManageHost | Kotlin class/object/interface |
| 2026-08-02 | AgentConfigDialog、EngineOption、EngineOptionCatalog、EnginePackageResolver、ExternalImportPreviewDialog | Kotlin class/object + @JvmStatic |

> 其中 `EngineOptionCatalog`/`EnginePackageResolver` 被 agent.md §8 引用为共享逻辑提取范例，需优先迁 Kotlin `object` + `@JvmStatic` 后方可作为完整范例。

#### 9.2 Java 单方法接口待迁 fun interface（7 个）

`CardLayoutSpec`、`LauncherAppPickerDialog.Callback`、`LauncherLaunchTargetPicker.Callback`、`GamePasswordDialog.OnPasswordSetListener`、`ScanDirectoryController.OnScanRequestedListener`、`GameSessionController.LaunchListener`、`ExternalImportController.ParseTask`

#### 9.3 LauncherActivity companion const val 待下沉（7 个）

`EXTRA_OPEN_ACCOUNT_LOGIN`、`EXTRA_PINNED_GAME_ID`、`EXTRA_FORCE_PORTRAIT_HOME`、`ACTION_LAUNCH_PINNED_GAME`、`LEGACY_ACTION_LAUNCH_PINNED_GAME` → `object LauncherIntents`；`KEY_STORAGE_PERMISSION_ASKED` → `LauncherPreferences`；`SPLASH_MIN_DISPLAY_MS` → `LauncherSplash`

#### 9.4 nav 取色重复 + 违规取色

`PadGameModeActivity:170-196`、`LauncherNavRenderer:255-283`、`HdModeActivity:184` 三处 nav 中心图标 `setColorFilter(Color.WHITE)`/`Color.GRAY` 重复，合并到 `LauncherNavRenderer`，未选中态改 `LauncherTheme.textMuted()`。

#### 9.5 core 侧架构违规

| 位置 | 问题 | 方案 |
|---|---|---|
| `LauncherGameLaunchBridge.kt:118` | core 内 `new AlertDialog` | 走 `LauncherUiBridge` 扩展方法 |
| `OverlayTranslationService.kt:70-78,286,325,605-656` | core Service 持有 `WindowManager` + 悬浮按钮/结果卡片 View | 界定平台覆盖层归属或迁 `com.apps` |
| `com/core/ui/` | 空目录 | 删除（core 不设 ui 子包） |

#### 9.6 core 长生命周期监听器未释放

`ArtemisLauncher.saveObservers`（FileObserver）、`OverlayTranslationService.captureThread`（HandlerThread）须补 `stop()`/`release()`/`quit()` 入口并在销毁时调用。

#### 9.7 CancellationException 吞没存量

`LauncherAppSettingsActivity.kt:225/229/289/293` — 4 处 `catch (_: Throwable)` 位于 `withContext(Dispatchers.IO)` 内，吞 CancellationException + OOM。改为 `catch (e: CancellationException) { throw e }` + `catch (e: OutOfMemoryError) { throw e }` + `catch (e: Exception)`。合规范本：`LauncherViewModel.kt:98-100/139-141/195-197/252-254`。

#### 9.8 catch 文件树遍历静默吞 Error

`AgentScanRootGateway:68/162/181/203/319/350/358`、`GameWorkspaceGateway:75/386/402/405/674/785/845`、`OpenAiCompatibleAgentClient:320` — `catch (Throwable ignored) { continue; }` 吞 `Error`，改 `catch (Exception)` 或 `catch (IOException | SecurityException)`。

#### 9.9 废弃 API LocalActivityManager（HD 6 Fragment）

`HdAccountFragment`、`HdSaveManagerFragment`、`HdHomeFragment`、`HdProfileFragment`、`HdManageFragment`、`HdSettingsFragment` — 迁子 Fragment 或 NavComponent，迁移时一并瘦身因转发 `dispatch*` 调用而膨胀的生命周期方法。

#### 9.10 其他存量

| 位置 | 问题 | 状态 |
|---|---|---|
| `LocalAgentMessageAdapter.kt:106` | ViewHolder 内 `dp()`（规范 §4 明确禁止） | 待迁移（优先） |
| `LauncherAiChatActivity:114/206` | 手拼 PopupWindow + Dialog（与 4.1/4.2 合并处理） | 待迁移 |
| ~~`LauncherSyncCenterActivity:211`、`LocalBackupController:94`~~ | ~~`throw new Exception` → `throw IOException`~~ | ✅ 已修复（Phase 10.1 BLOCKING） |
| `EngineOptionCatalog`/`EnginePackageResolver` | Java 存量，迁 Kotlin `object` + `@JvmStatic` | 待迁移 |
| `LauncherModuleCompatibilityActivity:319` | `openInstallPage` 裸 `Intent(ACTION_VIEW)`（已带 catch 兜底，非 §7.5 清单内） | 既有技术债 |
| `HdSettingsFragment` | `LocalActivityManager` 废弃 API（§8） | 既有技术债（§9.9 已列） |

#### 9.10.1 本地 `dp()` 副本存量分类（2026-08-02 校准，共 18 处）

> Phase 10.1 已删除 `PadDialogFactory.kt` 与 `LauncherDialogFactory.kt` 两处副本（原为 `LauncherTheme.dp` 薄包装）。剩余 18 处按迁移难度分类，建议按 ViewHolder 型 → 简单副本型 → 签名差异/接口契约型顺序渐进清理。

**ViewHolder 型（规范 §4 明确禁止，1 处，优先迁移）**

| 文件:行号 | 签名 | 迁移建议 |
|---|---|---|
| `agent/LocalAgentMessageAdapter.kt:106` | `fun dp(value: Int): Int = Math.round(value * itemView.resources.displayMetrics.density)` | 删除方法，调用点改 `LauncherTheme.dp(itemView.context, value)` |

**简单副本型（13 处，机械替换）**

| 文件:行号 | 签名 | 备注 |
|---|---|---|
| `PadUi/PadManageFragment.kt:690` | `private fun dp(value: Int): Int = LauncherTheme.dp(requireContext(), value)` | 已是薄包装，直接内联 |
| `game/LauncherLibraryFragment.kt:1091` | `private fun dp(value: Int): Int = LauncherTheme.dp(requireContext(), value)` | 已是薄包装，直接内联 |
| `home/LauncherHomeFragment.kt:647` | `protected open fun dp(value: Int): Int` | `protected open` 但无子类 override，可降级 private 或内联 |
| `settings/ResourceStationActivity.java:239` | `private int dp(int value)` | Activity 内标准副本 |
| `chat/LauncherPublicChatActivity.java:279` | `private int dp(int value)` | Activity 内标准副本 |
| `chat/LauncherAiChatActivity.java:411` | `private int dp(int value)` | Activity 内标准副本 |
| `PadUi/PadGameFragment.java:780` | `private int dp(int value)` | Fragment 内标准副本 |
| `agent/LocalAgentActivity.java:543` | `private int dp(int value)` | Activity 内标准副本 |
| `sync/LauncherSyncCenterActivity.java:263` | `private int dp(int value)` | Activity 内标准副本 |
| `settings/LauncherCustomVndbSearchDialog.java:365` | `private static int dp(Fragment, int)` | 入参为 Fragment |
| `agent/AgentConfigDialog.java:217` | `private static int dp(Activity, int)` | 入参为 Activity |
| `widget/AvatarCropActivity.java:222` | `private int dp(float value)` | 入参为 float，需 `value.toInt()` 或扩展 Float 重载 |
| `LauncherNavRenderer.kt:335` | `private fun dp(value: Int): Int` | 用 `host.resources`，可改 `LauncherTheme.dp(host, value)` |

**签名差异型（2 处，需评估扩展 helper 重载）**

| 文件:行号 | 签名 | 迁移建议 |
|---|---|---|
| `widget/LauncherWeeklyPlaytimeChartView.kt:86` | `private fun dp(value: Int): Float = value * resources.displayMetrics.density` | 返回 Float 用于 canvas 坐标，建议在 `LauncherTheme` 增 `dpFloat(context, value): Float` 重载 |
| `widget/LauncherTabletPortraitScaler.kt:36` | `@JvmStatic fun dp(context, baseDp: Int): Int`（带 `scaleFor` 缩放） | 并行工具非副本，是 `LauncherDialogFactory.dialogWidthPx` 调用目标；评估是否将 `LauncherTheme.dp` 的平板缩放分支统一委托到此处 |

**接口契约型（2 处，需改接口签名）**

| 文件:行号 | 签名 | 迁移建议 |
|---|---|---|
| `game/ManageHost.java:49` | `int dp(int value);`（接口方法） | 改默认方法委托 `LauncherTheme.dp` 或 `LauncherTabletPortraitScaler.dp` |
| `game/LauncherManageFragment.java:240` | `@Override public int dp(int value)`（带 `tabletPortraitScale()` 缩放） | 与上条联动，接口不变则无法删除 |

#### 9.11 runOnUiThread 守卫清单校准（更新阶段 3.2）

- **移出**：`LauncherSaveGameListActivity`（已通过 `isUiUnavailable()` 全部补齐 4 处）
- **校正**：`LauncherPublicChatActivity` 实际 **8 处回调**（242-249），非原记 1 处（243）
- **新增路径覆盖**：阶段 3.2 守卫范围扩展到 `RxMainScheduler.post`、`mainQueue.post`/`getMainQueue().post`，以及 core Bridge `postToMain` 上行回调实现方 lambda

#### 9.12 com.core 跨模块技术债细化（补充 8.3）

| 字面量 | core 内计数 | 涉及文件 |
|---|---|---|
| `"yukihub_prefs"` | 13 处 | ArtemisLauncher/KrkrLauncher/EngineSaveLocations/ScriptEngineLaunchers/UiScaleUtil/DevLogger/OverlayTranslationService/AgentScanRootGateway/SyncManager/LauncherUserData/LauncherAuthBridge/LauncherBridgeSupport/LauncherKrkrBridge |
| `"kr_engine_version"` | 7 处 | LauncherKrkrBridge/LauncherGameLaunchBridge/SyncManager/LauncherUserData 等 |
| `LauncherUserData.MAIN_PREF_KEYS` | 17 个偏好键 | LauncherUserData |
| 引擎包名路由（`"com.core.tyrano"`/`"internal.tyrano"` 等） | core 14 处 + com.apps 重复 | ExternalGameLaunchers/GameSaveFileManager/LauncherGameLaunchBridge/AgentScanRootGateway/GameWorkspaceGateway + com.apps 侧 LauncherSaveCategoryActivity/GameSessionController |

**方案**：设立 `com.core.CorePreferences` object（偏好名/键，注明主源在 `LauncherPreferences`）；引擎包名下沉 `com.core.launcher.EnginePackages`。

### 阶段 12：2026-08-03 阶段 4 手写弹窗清零整改记录

> 本轮依据阶段 4（4.1-4.7）执行：3 agent 并行修改 + 交叉核对 + 编译验证（`./gradlew :app:compileDebugJavaWithJavac :app:compileDebugKotlin` ✅ + `git diff --check` ✅ + 分层 0 命中 ✅）。

#### 12.1 已修复项

| 子项 | 文件 | 结果 |
|------|------|------|
| 4.1 | chat/LauncherAiChatActivity showCustomLlmDialog 95 行手拼 | ✅ 抽为 AgentLlmConfigDialog.java（透明 window + launcher_dialog_bg + applyPrimaryTone + 宽度兜底 288dp），Activity -196 行 |
| 4.2 | chat/LauncherAiChatActivity showMoreMenu PopupWindow | ✅ 固定 dp(119) 改屏幕兜底 min(119dp, screen-48dp)；Color.TRANSPARENT 纯遮罩语义保留并注释 |
| 4.3 | game/LauncherLaunchTargetPicker AlertDialog 手拼 | ✅ 迁移 LauncherDialogFactory（showLoading + showSingleChoice + showInfo）；API/调用方零改动 |
| 4.5 | theme/LauncherDialogFactory showScanDepthChoices ●/○ 拼接 | ✅ 改用 compactChoice 选中态 |
| 4.7 | game/ExternalImportPreviewDialog 裸 dp(300) | ✅ 改 LauncherDialogFactory.dialogWidthPx(ctx, 300) 兜底（W2' 上下文修正：原记 PadDialogFactory 为误记，ExternalImportPreviewDialog 在 LauncherActivity 手机端调用，应走竖屏工厂 32dp 边距 + 平板竖屏缩放） |
| 4.4 | widget/AvatarCropActivity buildRoot 外壳 | ✅ 标题改 LauncherTheme.text(this)；按钮为纯文字样式无背景，避免视觉变化未强制替换 |

#### 12.2 已登记偏差 / 遗留（供后续阶段）

- **P3 语言约束**：`AgentLlmConfigDialog.java` 为新增 Java 文件，违反 agent.md §2（新增 Dialog 工具类用 Kotlin）。已登记为审批偏差，随 §9.1 语言约束迁移阶段迁 Kotlin（可与 AgentConfigDialog 等一并处理）。
- **P3/INFO**：AgentLlmConfigDialog 平板端宽度经 LauncherTabletPortraitScaler 缩放但树内 padding/行高未走全树缩放，平板视觉需实机确认。
- **INFO**：4.2 的 LauncherMenuSheet 抽取、4.4 的 activity_avatar_crop.xml+ViewBinding、4.6 的 LauncherDialogParts 共享基件未执行（工程量大，3 个专用 Dialog 手拼重复：AgentConfigDialog / LauncherCustomVndbSearchDialog / ExternalImportPreviewDialog），建议与 §9.1 语言约束迁移（3 文件迁 Kotlin）合并执行，一并抽共享表单基件。

#### 12.3 阶段 4 收尾审查整改记录（W1'–W3'，2026-08-03）

> 阶段 4 落地后多轮交叉审核发现的 WARNING 修复归档。3 agent 并行修改 + 交叉核对 + 编译验证（`./gradlew :app:assembleDebug` ✅ + `git diff --check` ✅ + 分层 0 命中 ✅）。

| 轮次 | WARNING | 修复内容 |
|------|---------|---------|
| W1' | `LauncherAiChatActivity.java:18,23` unused import（`AlertDialog`/`RecyclerView`） | ✅ 删除，连带清理 6 个 unused import（`Window`/`WindowManager`/`EditText`/`ContextCompat`/`LlmConfigCallback`/`LlmConfig`/`Locale`/`URISyntaxException`） |
| W2' | `AgentLlmConfigDialog.java` 新建 Java 违反 §2 语言约束 | 维持 Java，已登记 P3 偏差（见 §12.2） |
| W3' | `AgentLlmConfigDialog.java:245-250` `dialogWidthPx` 私有副本 | ✅ 删除本地副本，`LauncherDialogFactory.dialogWidthPx` 提升为 `@JvmStatic fun` 公开 API + KDoc（公式位级等价，行为零变更） |
| W4' | `GameWorkspaceGateway.java:402` `catch (IOException ignored)` 遗漏 `SecurityException`；`LauncherScanBridge.kt:269` 遗漏 `IllegalArgumentException` | ✅ `:402` 改 `catch (IOException \| SecurityException ignored)` + 紧邻注释；`:269` 拆双 catch `IllegalArgumentException` + `SecurityException` 各带注释。连带收窄全文件 4 处 `catch (Throwable)`（`:75`/`:677`/`:788`/`:848`） |
| W5' | `LauncherLaunchTargetPicker.java` loading cancelable true→false 行为变更 | ✅ 完全重构为 `LauncherDialogFactory.showLoading`（工厂契约 `cancelable=false`），注释说明"生命周期由本方法管理" |
| W1'' | `GameWorkspaceGateway.java:75` `catch (SecurityException ignored)` 同模式漏补注释 | ✅ 补紧邻注释（与 `:387` 同义） |
| W2'' | `ExternalImportPreviewDialog.java:54` 竖屏上下文调用 `PadDialogFactory.dialogWidthPx` | ✅ 改为 `LauncherDialogFactory.dialogWidthPx`（比预期更彻底——原是裸 `host.dp(300)` 无兜底，现修复为工厂 API + 紧邻注释） |

**遗留 INFO（不阻塞，供后续阶段）**：
- `AgentLlmConfigDialog.java:137` `fetchLlmConfig.onError` 仍仅 `if (dialog.isShowing())`，未补 `isFinishing()/isDestroyed()`（仅更新 dialog 内 TextView，风险低）
- `AgentLlmConfigDialog.java:235` `catch (URISyntaxException | NumberFormatException ignored)` 缺紧邻注释
- `AgentLlmConfigDialog.java:244` `dialogText()` 用 `ContextCompat.getColor` 直接取色（搬迁存量，随 W2' Kotlin 迁移一并整改）
- `LauncherAiChatActivity.java:3` `import android.content.Intent;` unused（存量遗留）
- `LauncherAiChatActivity.java:227` `private int dp(int value)` 副本仍保留（§9.10.1 已登记 18 处存量）
- `LauncherModuleCompatibilityActivity.java:319` `new Intent(ACTION_VIEW, ...)` 绕过 `LauncherUrlOpener`（阶段 1 未覆盖，既有技术债）
- `GameWorkspaceGateway.java:677/788/848` 三处 catch 有兜底返回值但无注释（可选补齐）

#### 12.4 实机测试说明（2026-08-03 暂存区，阶段 4 + 阶段 3）

> 本轮暂存区 25 文件改动（+431/-351）覆盖阶段 4（手写弹窗清零 4.1/4.2/4.3/4.4/4.5/4.7）+ 阶段 3（catch 收窄 + runOnUiThread 守卫）+ W1'–W2'' 收尾审查整改。构建已通过 `./gradlew :app:assembleDebug`，以下为实机验证清单。

##### 0. 测试准备

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动 logcat 监控（关注 ClassCastException/IllegalState/NotFoundException/ActivityNotFound/SecurityException 等）
adb logcat -c && adb logcat | grep -E "AndroidRuntime|FATAL|System\.err|DevLogger|LauncherApplication|com\.apps|com\.core"
```

**测试设备建议**：
- 竖屏手机（Android 10+，验证弹窗宽度兜底、桥回调守卫）
- 平板横屏（Pad/HD 模式，验证 PadDialogFactory 与 LauncherDialogFactory 上下文区分）
- 平板竖屏（验证 `LauncherTabletPortraitScaler` 缩放）
- 深色/浅色模式各一遍

##### 1. AgentLlmConfigDialog 提取回归（P0，必测）

**测试场景**：进入 AI 聊天页 → 点击右上角更多菜单 → 选择「自定义模型」

**验证点**：
- 弹窗正常弹出，宽度 288dp（`WIDTH_FORM_DP`），透明 window + `launcher_dialog_bg` 背景
- 4 个输入框（API 端点 / API Key / 模型名 / Temperature）正常显示
- 「读取配置」按钮：异步加载已有配置回填，loading 状态切换正常
- 「恢复默认」按钮：清空输入并回填默认值
- 「保存」按钮：校验通过后异步保存，成功 Toast `social_model_saved`
- **URL 校验**：
  - 输入 `ftp://...` → 提示 `social_error_http_only`
  - 输入 `http://localhost:8080` → 提示 `social_error_private_endpoint`
  - 输入 `http://192.168.1.1` → 提示 `social_error_private_endpoint`
  - 输入 `https://api.example.com/v1` → 校验通过
  - 输入非法 URI（如 `not a url`）→ 提示 `social_error_http_endpoint`
- **Temperature 校验**：输入 `-1` 或 `3` → 提示 `social_temperature_error`；输入 `0.7` → 通过
- **IME 唤起**：点击输入框后 180ms + 420ms 两次 `showSoftInput`，软键盘正常弹出
- **快速返回**：弹窗显示中按返回键 → dismiss，无崩溃（`dialog.isShowing()` 守卫）
- **配置生效**：保存后发送一条消息，确认使用新配置的模型/端点

**阻断条件**：弹窗不弹出、输入校验失效、保存后配置未生效、IME 不弹出、返回崩溃。

##### 2. LauncherLaunchTargetPicker 弹窗工厂迁移（P0，必测）

**测试场景**：游戏编辑页 → 选择启动目标文件

**验证点**：
- **loading 弹窗**：显示「正在扫描」，**不可取消**（`cancelable=false`，工厂契约），扫描完成后自动 dismiss
  - 注：若扫描耗时较长，用户无法手动关闭 loading，需确认 UX 可接受
- **扫描成功**：弹出单选列表（`showSingleChoice`），可选择目标文件
- **空目标**：弹出 `showInfo` 提示「未找到可启动文件」，按钮「知道了」
- **快速返回**：扫描中按返回键 → Activity finish，loading 自动 dismiss（`isFinishing()` 守卫）
- **多次触发**：连续点击选择目标，无崩溃（防抖 + 守卫）

**阻断条件**：loading 不消失、扫描完成后弹窗不弹出、返回崩溃。

##### 3. ExternalImportPreviewDialog 宽度兜底（P0，必测）

**测试场景**：外部导入游戏 → 预览导入内容

**验证点**：
- **窄屏手机**（屏宽 < 332dp）：弹窗宽度 = `min(300dp, 屏宽-32dp)`，不溢出屏幕
- **正常手机**（屏宽 ≥ 332dp）：弹窗宽度 300dp
- **平板竖屏**：弹窗宽度走 `LauncherTabletPortraitScaler.dp` 缩放，按比例放大
- **内容布局**：CheckBox 列表 + 文本 + 底部三按钮（取消/全选/导入）布局不错位
- **长文本**：游戏名/路径 `singleLine + ellipsize=end`，不挤压 CheckBox

**阻断条件**：弹窗溢出屏幕、内容截断、按钮不可点击。

##### 4. LauncherDialogFactory.dialogWidthPx 公开 API 回归（P1）

**测试场景**：触发所有走 `LauncherDialogFactory.dialogWidthPx` 的弹窗

**验证点**：
- AI 聊天 → 自定义模型弹窗（288dp）
- 外部导入预览弹窗（300dp）
- `LauncherDialogFactory` 内部 `open`/`setContent` 调用（确认/选择/信息等弹窗宽度正常）
- **平板竖屏**：所有弹窗宽度走 `LauncherTabletPortraitScaler.dp` 缩放
- **窄屏**：所有弹窗宽度走 `min(期望宽度, 屏宽-32dp)` 兜底

**阻断条件**：弹窗宽度异常、平板缩放失效、窄屏溢出。

##### 5. compactChoice 选中态样式（P1）

**测试场景**：设置 → 扫描深度选择（`showScanDepthChoices`）

**验证点**：
- 选中项：`primary` 色 + bold
- 未选中项：`text` 色 + normal
- 不再出现「●/○」文本前缀
- 切换选中态后颜色立即更新

**阻断条件**：选中态颜色异常、文本前缀残留。

##### 6. AvatarCropActivity 取色统一（P1）

**测试场景**：头像编辑 → 裁剪页

**验证点**：
- 标题文字颜色走 `LauncherTheme.text(this)`（与页面其他文字一致）
- 深色/浅色模式切换后颜色正确

**阻断条件**：标题颜色异常、深色模式不可读。

##### 7. catch 收窄回归（P1，com.core 12 文件）

**测试场景**：触发各类异常路径

**验证点**：
- **AgentScanRootGateway**：扫描敏感目录（如 `/proc`）→ `SecurityException` 被捕获跳过，不崩溃
- **GameWorkspaceGateway**：搜索文件时遇到无权限目录 → `SecurityException` 单文件失败隔离，不影响整体搜索
  - 搜索大文件超 `MAX_SEARCH_FILE_BYTES` → 跳过该文件
  - 搜索遇到非文本文件 → decode 失败跳过
- **LauncherScanBridge**：
  - 引擎探测失败 → `catch (Exception)` 兜底，返回 null
  - SAF tree URI 非法 → `catch (IllegalArgumentException)` 单文件隔离
  - SAF 封面目录权限失效 → `catch (SecurityException)` 单文件隔离
- **LauncherMetadataBridge**：元数据抓取失败 → `catch (Exception)` + 注释，不崩溃
- **LauncherCoverBridge**：封面同步失败 → `catch (Exception)`，不崩溃
- **LauncherPublicChatBridge**：事件解析失败 → `catch (Exception)`，不崩溃
- **GameRepository**：URI 路径归一化失败 → `catch (IllegalArgumentException)`，保留原始字符串
- **AgentSnapshotStore**：快照损坏 → `catch (Exception)` 跳过，不影响其他快照
- **OpenAiCompatibleAgentClient**：版本号解析失败 → `catch (NumberFormatException)`，兜底 0
- **LocalAgentRuntime**：JSON 结果解析失败 → `catch (JSONException)`，返回兜底文案

**阻断条件**：异常路径崩溃、单文件失败导致整体任务中断。

##### 8. runOnUiThread 守卫回归（P1）

**测试场景**：触发快速返回与异步回调

**验证点**：
- **LauncherPublicChatActivity**：进入公共聊天 → 立即按返回键 → 心跳/消息回调无崩溃（8 处 `isUiUnavailable()` 守卫）
- **LauncherAiChatActivity**：进入 AI 聊天 → 发送消息后立即按返回键 → `loadHistory`/`sendMessage` 回调无崩溃（4 处完整守卫）
- **TranslationSettingActivity**：翻译设置 → 测试翻译后立即按返回键 → `runOnUiThread` 守卫生效
- **LauncherActivity**：首页 → 桌面快捷方式启动回调 → `PinnedGameShortcut.onResult` 守卫
- **PadManageFragment**：平板游戏库 → 滚动后立即按返回键 → `post` 守卫
- **PadGameFragment**：平板游戏页 → 卡片高度更新 → `post` 守卫
- **LauncherLibraryFragment**：游戏库 → 搜索防抖 → `Runnable` 守卫

**阻断条件**：快速返回崩溃、异步回调更新已销毁 UI。

##### 9. LauncherHomeFragment catch 收窄（P2）

**测试场景**：首页加载头像

**验证点**：
- 头像加载失败（如网络错误）→ `catch (RuntimeException)` 兜底，不崩溃
- OOM 场景：`OutOfMemoryError` 不被捕获（Error 子类），向上传播（解码在 `SafeImageLoader` 内部完成）

**阻断条件**：头像加载崩溃。

##### 测试优先级与建议顺序

1. **P0 必测**：1（AgentLlmConfigDialog）→ 2（LauncherLaunchTargetPicker）→ 3（ExternalImportPreviewDialog）
2. **P1 重要**：4（dialogWidthPx 公开 API）→ 5（compactChoice）→ 6（AvatarCrop）→ 7（catch 收窄）→ 8（守卫回归）
3. **P2 回归**：9（HomeFragment catch）

**通过标准**：P0 全部通过 + P1 无阻断 + P2 无新增异常。logcat 无 `FATAL EXCEPTION`、无 `SecurityException` 未捕获、无 `IllegalStateException` 守卫类崩溃。

##### 关键风险点（重点观察）

1. **AgentLlmConfigDialog 平板缩放**：宽度走 `LauncherTabletPortraitScaler.dp` 但 padding/字号走 `LauncherTheme.dp`（不缩放），平板竖屏视觉可能不一致（§12.2 已登记 INFO）
2. **LauncherLaunchTargetPicker loading 不可取消**：扫描期间用户无法手动关闭 loading，长扫描场景需确认 UX
3. **ExternalImportPreviewDialog 边距变化**：从无兜底（裸 `host.dp(300)`）改为 `LauncherDialogFactory.dialogWidthPx`（32dp 边距），窄屏手机弹窗会变宽，确认内容布局自适应
4. **catch 收窄后异常传播**：部分原 `catch (Throwable)` 改为具体异常，若漏收窄某异常类型可能导致崩溃（重点测 7 的异常路径）

### 阶段 11：2026-08-03 阶段 3 异常收窄 + 守卫补齐整改记录

> 本轮依据阶段 3（3.1 catch(Throwable) 收窄 / 3.2 runOnUiThread 守卫 / 9.7 CancellationException / 9.8 文件树遍历 / 9.11 守卫校准）执行，先 4 agent 并行修改、再交叉核对（发现编译错误与回归风险）、修复后最终审查通过。构建验证：`./gradlew :app:compileDebugJavaWithJavac :app:compileDebugKotlin` ✅ + `git diff --check` ✅ + 分层 `grep import com.apps com/core` 0 命中 ✅。

#### 11.1 已修复项

| 类别 | 项目 | 结果 |
|------|------|------|
| 守卫补齐（com.apps） | LauncherPublicChatActivity 8 处桥回调（loadInitial/loadStatus/loadAnnouncements/loadOlder/send×2/onAnnouncementChanged/心跳）+ AiChat 4 处 WEAK→完整守卫 + TranslationSettingActivity:196 + LauncherActivity:272(PinnedGameShortcut onResult) + LauncherLibraryFragment/PadManageFragment 防抖+post + PadGameFragment:175 post | ✅ 全部补 `isUiUnavailable()` / `isFinishing\|\|isDestroyed` / `!isAdded\|\|_binding==null` |
| catch 收窄（com.apps） | LauncherHomeFragment:621 `catch(Throwable)` → `catch(RuntimeException)` + 注释 | ✅ |
| 文件树遍历（com.core） | AgentScanRootGateway 7 处、GameWorkspaceGateway 7 处、OpenAiCompatibleAgentClient:320、LocalAgentRuntime:608 → 具体异常（SecurityException/IOException/IllegalArgumentException/JSONException/NumberFormatException） | ✅ 事务 rethrow（AgentScanRootGateway:215、GameWorkspaceGateway:516/519/521）与进程边界（LocalAgentRuntime:413/463）保持 Throwable 合规。GameWorkspaceGateway 3 处 catch 补紧邻注释（W1'/W1''，见 §12.3） |
| 静默空体（com.core） | LauncherScanBridge 8 处、LauncherMetadataBridge 4 处、LauncherCoverBridge 3 处、GameRepository 3 处、AgentSnapshotStore 4 处、LauncherAuthHttpClient:175、LauncherDiagnosticsBridge:92、LauncherPublicChatBridge:130/141 → 具体异常 + 中文注释 | ✅ |
| 审查发现修复 | ① `catch(SecurityException\|IOException)` 包 DocumentFile 方法（不抛 checked IOException）编译失败 → 去 IOException；② AgentScanRootGateway:162 空体遗漏（IDE 缓冲与磁盘不同步，perl 直改落盘）；③ LauncherCoverBridge:46 空体遗漏；④ LauncherPublicChatBridge:130 `data!!` NPE 会逃逸 OkHttp 线程断连 → `catch (e: Exception)`；⑤ AgentSnapshotStore read() 抛 IOException → `catch (e: Exception)` 保持跳过损坏快照语义；⑥ 4 处非空 Throwable 宽捕获（ScanBridge:151/173、MetadataBridge:81、PublicChatBridge:141、CoverBridge:133）收窄 | ✅ |

#### 11.2 校验结论

- 守卫清单校准（9.11）：LauncherSaveGameListActivity 已通过 isUiUnavailable 补齐（移出未守卫）；LauncherPublicChatActivity 实际 8 处回调已全部覆盖。
- CancellationException（9.7）：扫描确认 com.apps 协程内 catch 均已有 `catch (e: CancellationException) { throw e }` 前置重抛（LauncherViewModel×4、LauncherAppSettingsActivity×2、LauncherHomeFragment:453），无需修改。
- 剩余存量（阶段 3 范围外，供后续阶段）：com.core 仍存 60+ 处 `catch (_: Throwable)`（EngineDetector/External*PluginStrategy/KrkrLauncher/ScriptEngineLaunchers 等），属 9.8/9.12 后续批次。

### 阶段 10：2026-08-02 收尾审查整改记录

> 本轮代码审查（W1–W6 + INFO）整改归档。BLOCKING 与 W1–W6 均已修复，随 Phase 5.3+8.1（共 51 文件）一并落地；本节记录整改结果与遗留建议。

#### 10.1 已修复项

| 级别 | 项目 | 整改结果 |
|------|------|----------|
| BLOCKING×2 | LocalBackupController.java:94、LauncherSyncCenterActivity.java:211 `throw new Exception` → `throw new IOException` | 已修复 |
| W1 | LauncherHomeFragment.kt 裸 `startActivity(ACTION_VIEW)` + `catch(Throwable)` | 已迁移 `LauncherUrlOpener.open`（scheme 白名单 + 失败 Toast） |
| W2 | PadDialogFactory/LauncherDialogFactory 重复的 `showUpdateAvailable`/`emptyOr`/`trimUpdateBody` | 已提取为 `LauncherUpdateFormatter`（theme/LauncherUpdateFormatter.kt），两工厂共用 |
| W3 | configureEdgeToEdgeWindow 迁移后 Color/Window/WindowManager/ContextCompat import 残留 | 已清理 11 处文件（LauncherAiChatActivity 的 import 仍被弹窗逻辑使用，非残留） |
| W4 | PadDialogFactory.kt 单文件超 500 行 | 已拆分，当前 469 行（更新对话框拆出为 PadUpdateDialog.kt 66 行） |
| W5 | PadGameFragment 子弹窗宽度 | 已对齐 §6 标准（270/288dp）；需实机确认横屏平板视觉 |
| W6 | LocalAgentActivity 独立 configureEdgeToEdgeWindow | 保留独立实现，理由已标注于 §8.1 表格 |
| §1 | PadDialogFactory/LauncherDialogFactory 本地 `private fun dp()` 副本 | 已删除包装，直接调用 `LauncherTheme.dp`（两重载公式相同，语义等价） |

#### 10.2 遗留建议（INFO）

- **提交粒度**：本次提交混合多关注点（Phase 5.3 + 8.1 + W1–W6 + INFO 整改共 59 文件），建议后续按阶段拆分 commit
- **URL 确认**：`LauncherUpdateFormatter.FALLBACK_RELEASE_URL` 含 "test" 标签（`https://github.com/Weiss-UltimateSavior/RinneMobile/releases/tag/test`），KDoc 已注明"与 LauncherModuleCompatibilityActivity 等处使用的 test 标签保持一致，不可擅自修改"；仍需确认是否为正式发布地址，或改为 `/releases/latest` 通用链接
- **`LauncherUpdateFormatter` 注解**：公开方法（`buildUpdateMessage`/`emptyOr`/`trimUpdateBody`/`resolveUpdateUrl`）未加 `@JvmStatic`。当前调用方仅 Kotlin（`PadUpdateDialog.kt`、`LauncherDialogFactory.kt`），无 Java 调用方，非必须；为与项目内其他 `object` 风格一致可补加，若后续有 Java 调用方再补亦可
- **大文件跟踪**：`LauncherDialogFactory.kt` 当前 774 行（含多个 `showXxx` 重载），待阶段 5.1 渐进瘦身（拆 `Confirm/Choice/Loading/Update` 子 object）；`PadGameFragment.java` 783 行（Phase 5.3 迁移后实现 7 个回调净增 178 行），待阶段 5.1 拆 `PadGamePagingController/PadGameAvatarRenderer`
- **既有技术债（本次未引入，不阻塞）**：
  - `HdSettingsFragment` 仍用 `LocalActivityManager`（§9.9 已列）
  - 本地 `dp()` 副本残留 18 处，完整分类与迁移建议见 §9.10.1（ViewHolder 型 1 处优先、简单副本型 13 处机械替换、签名差异型 2 处需扩展 helper、接口契约型 2 处需改接口）
  - `LauncherModuleCompatibilityActivity:319` `openInstallPage` 裸 `Intent(ACTION_VIEW)`（已带 `catch (ActivityNotFoundException | SecurityException)` 兜底，非 §7.5 迁移清单内）
  - `ACTION_VIEW`/`FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` 在未迁移文件中残留（`ResourceStationActivity`、`PadSettingsActivity`、`PadGameModeActivity`、`HdModeActivity` 等，属存量）
  - `PadDialogFactory.kt:470`、`LauncherDialogFactory.kt:746` 的 `private fun dp()` 已删除（§10.1 已记录）

#### 10.3 实机测试说明（2026-08-02 暂存区）

> 本轮暂存区 59 文件改动（+831/-1039）覆盖 Phase 5.3（删 `LauncherGameActionController`）、Phase 8.1（13 处 EdgeToEdge 迁移）、W1–W6 + INFO 整改（`throw Exception` 收窄、`LauncherUrlOpener` 推广、`LauncherUpdateFormatter` 提取、`PadUpdateDialog` 拆分、dp 副本删除、5 个 account/profile 文件 import 清理）。构建已通过 `./gradlew :app:assembleDebug`，以下为实机验证清单。

##### 0. 测试准备

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动 logcat 监控（关注 ClassCastException/IllegalState/NotFoundException/ActivityNotFound 等）
adb logcat -c && adb logcat | grep -E "AndroidRuntime|FATAL|System\.err|DevLogger|LauncherApplication|com\.apps"
```

**测试设备建议**：
- 竖屏手机（Android 10+，覆盖状态栏/导航栏 inset）
- 平板横屏（Pad/HD 模式，验证 §6 弹窗宽度兜底与 nav 取色）
- 含刘海/药丸屏设备（验证 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，主要影响 `LocalAgentActivity`）
- 深色/浅色模式各一遍

##### 1. Phase 5.3：PadGameFragment 长按游戏菜单（P0，必测）

**测试场景**：平板横屏 → 游戏库 → 长按任一游戏卡片

**验证点**：
- 主菜单项集合与顺序：详情 → 游玩状态 → 收藏 → 密码 → 更多选项 → 取消（与原 `LauncherGameActionController` 一致）
- 不应出现「编辑」项（`includeEditAction=false`）
- 菜单宽度 270dp，长游戏名 `singleLine + ellipsize=end` 不挤压底部取消按钮
- 点击「详情」：弹出 288dp 详情对话框，长 URI/包名应能内部滚动（`maxLines=14` + `ScrollingMovementMethod`）
- 点击「游玩状态」：弹出单选列表（横屏走 `PadDialogFactory.showSingleChoice`），选中后卡片状态刷新
- 点击「收藏」：卡片收藏图标立即更新（`toggleFavorite` 走 `AppExecutors.runOnSingle` + `binding == null` 守卫）
- 点击「密码」：跳转 `GamePasswordLock` 设置/取消密码，回调后卡片刷新
- 点击「更多选项」：子菜单（编辑游玩时长 → 添加到桌面 → 重新匹配 → 自定义 VNDB → 同步 → [ONS 引擎才显示] ONS 设置 → 删除 → 取消）
- 点击「删除」：270dp 危险确认弹窗（红色删除按钮），确认后卡片从列表移除
- 快速连续长按多个游戏：无崩溃（`reloadGameInPlace`/`removeGameInPlace` 守卫生效）
- ONS 引擎游戏才显示「ONS 设置」：点击应正常跳转 `LauncherKrkrSettingsActivity`（ONScripter 配置页），失败走 `DevLogger.w` + Toast `game_action_ons_open_failed`

**阻断条件**：菜单项缺失/顺序错乱、回调不触发、弹窗宽度异常、Fragment destroy 后崩溃。

##### 2. Phase 8.1：13 处 EdgeToEdge 迁移（P0，必测）

**测试场景**：逐个进入下列页面，验证状态栏/导航栏沉浸式效果、深浅模式切换、屏幕旋转

| # | 页面 | 入口 | 关键验证 |
|---|------|------|---------|
| 1 | LauncherActivity | 启动 App | 状态栏透明、nav bar 用 `launcher_bottom_bar_color`、首页粒子背景延伸到状态栏 |
| 2 | LauncherSyncCenterActivity | 设置 → 同步中心 | 状态栏透明、深浅模式切换后立即生效 |
| 3 | LauncherToolboxActivity | 设置 → 工具箱 | 同上 |
| 4 | LauncherAppSettingsActivity | 设置 → 应用设置 | 同上 |
| 5 | LauncherKrkrSettingsActivity | 长按游戏 → 更多 → ONS 设置 | 同上 |
| 6 | LauncherMetadataSourceActivity | 设置 → 元数据源 | 同上 |
| 7 | AvatarCropActivity | 头像编辑 → 裁剪 | 状态栏透明 + ActionBar 隐藏（`getSupportActionBar().hide()` 保留） |
| 8 | LauncherChatSelectActivity | 聊天列表 | 同上 |
| 9 | TranslationSettingActivity | 设置 → 翻译设置 | 同上 |
| 10 | LocalAgentActivity | 设置 → 本地智能体 | **特殊**：保留独立 e2e 实现，验证 WindowCompat insets、刘海 SHORT_EDGES、contrast enforced 在含刘海设备上正常 |
| 11 | LauncherThemeMenuActivity | 设置 → 主题 | 同上 |
| 12 | LauncherPublicChatActivity | 进入公共聊天 | **luminance 模式**：状态栏图标颜色按 `LauncherTheme.primary` 亮度决定（亮主色→深色图标，暗主色→浅色图标） |
| 13 | LauncherAiChatActivity | 进入 AI 聊天 | 同上 |

**通用验证**：
- 切换深色/浅色模式（设置 → 深色模式），状态栏图标颜色立即翻转
- 屏幕旋转（横竖屏切换），状态栏布局不错位
- 含刘海设备：内容不被刘海遮挡（尤其 LocalAgentActivity）

**阻断条件**：状态栏白底白字、nav bar 颜色异常、旋转后布局错位、刘海遮挡内容。

##### 3. W1：LauncherHomeFragment 外链打开（P0）

**测试场景**：首页 → 点击任一外链（如公告/资源站链接）

**验证点**：
- `http://`/`https://` 链接正常打开系统浏览器
- 非 http(s) scheme 链接（如 `intent://`、`market://`）应弹 Toast `home_cannot_open_link`，不崩溃
- 无对应 Activity 的链接应弹同样的 Toast（`LauncherUrlOpener.open` 返回 false）

**阻断条件**：点击外链无响应/崩溃、白名单外 scheme 静默失败。

##### 4. W2：HD 横屏更新检查弹窗（P1）

**测试场景**：HD 横屏 → 设置 → 检查更新

**验证点**：
- 弹窗宽度走 `PadDialogFactory` 标准（270/288dp），不是竖屏 252dp
- 有更新时：展示「当前版本 / 最新版本 / 更新日志」，提供「前往下载 / 查看发布页 / 稍后」选项
- 无更新时：`showInfo` 提示「已是最新版本」
- 检查失败时：`showInfo` 提示错误信息
- 点击「前往下载」/「查看发布页」走 `LauncherUrlOpener.open`，失败有 Toast
- 竖屏手机同样入口（设置 → 检查更新）应走 `LauncherDialogFactory`（252dp），与横屏宽度有差异

**阻断条件**：横屏误用竖屏工厂（宽度 252dp）、URL 打开失败无提示、更新日志拼接乱码。

##### 5. W3 + INFO 3：import 清理 + dp 副本删除（P1，编译已验证，实机观察）

**测试场景**：本轮清理了 11 + 5 = 16 个文件的 unused import + 删除两个 factory 的 `private fun dp()`

**验证点**：
- 编译通过不代表运行时无 `NoClassDefFoundError`/`NoSuchMethodError`，重点观察：
  - 进入任一 Pad/HD 弹窗（确认、加载、菜单、单选、危险确认、信息提示），弹窗内间距/高度/宽度视觉无变化（dp 公式位级一致）
  - 进入竖屏弹窗（`LauncherDialogFactory` 各 `show*`），同上
- 16 个清理 import 的文件逐个进入对应页面，无 `ClassNotFoundException`：
  - account 包：账户设置、注册、找回密码
  - profile 包：模块兼容性、资料编辑
  - settings 包：资源站、元数据源、工具箱、Krkr 设置
  - sync 包：同步中心
  - chat 包：聊天选择、公共聊天、AI 聊天
  - theme 包：主题菜单
  - widget 包：头像裁剪

**阻断条件**：任一页面进入崩溃、弹窗尺寸异常。

##### 6. W4：PadDialogFactory 拆分后所有 Pad 弹窗（P1）

**测试场景**：平板横屏 → 触发各类 Pad 弹窗

**验证点**：
- 双按钮确认（`showConfirm` 288dp 水平并排按钮）
- 普通确认/信息/加载/菜单/单选/危险确认（`showStandardConfirm` 等 270dp 垂直按钮）
- 修改游玩时长（专用实现，IME 唤起正常）
- 同步进度（专用实现，`sync_progress` tag 后台更新）
- 文件访问权限（专用实现，Android 版本分支）
- 游戏详情（288dp 详情容器，长 URI 内部滚动）
- 更新结果（`PadUpdateDialog.showUpdateResult` 三分支：error/hasUpdate/else）

**阻断条件**：弹窗宽度异常、按钮布局错位、IME 不弹出、同步进度卡死。

##### 7. W5：PadGameFragment 子弹窗宽度（P1）

**测试场景**：Phase 5.3 迁移后，子弹窗宽度从 320~360dp 收窄到 270~288dp

**验证点**：
- 详情对话框（288dp）：长 URI/包名能内部滚动，不被截断
- 编辑游玩时长（288dp）：双输入框布局不错位，IME 唤起正常
- 动作菜单/更多选项（270dp）：长游戏名 `singleLine + ellipsize=end`
- 删除确认（270dp）：红色删除按钮 + 取消按钮垂直布局

**阻断条件**：内容截断不可读、输入框被键盘遮挡、按钮不可点击。

##### 8. catch 收窄 + runOnUiThread 守卫回归（P1）

**测试场景**：触发各类异常路径与快速返回

**验证点**：
- `LocalBackupController`/`LauncherSyncCenterActivity` `throw IOException` 路径：同步备份失败时弹错误提示，不崩溃（外层 `catch (Exception)` 正确捕获）
- `PadGameFragment` `openOnsGameSettings` 失败：`catch (ActivityNotFoundException | IllegalArgumentException)` + `DevLogger.w` + Toast
- 快速返回测试：
  - 进入游戏库 → 长按游戏 → 立即按返回键 → 再次进入：无 `IllegalStateException`（`binding == null` 守卫生效）
  - 进入同步中心 → 开始同步 → 立即按返回键：无 UI 更新崩溃（`isUiUnavailable()` 守卫）
  - 进入公共聊天 → 收到心跳回调 → 立即按返回键：无崩溃（`runOnUiIfAlive` 包装 + `isUiUnavailable()` 守卫）
- `LauncherAppSettingsActivity` 协程取消：返回时 `CancellationException` 正确重抛，无协程泄漏

**阻断条件**：异常路径崩溃、快速返回崩溃、协程取消信号被吞。

##### 9. LauncherUrlOpener 推广回归（P2）

**测试场景**：本轮新增 5 处 `LauncherUrlOpener.open` 调用

**验证点**：
- `ResourceStationActivity`：点击资源站外链正常打开
- `LauncherMetadataSourceActivity`：点击元数据源外链正常打开
- `LauncherToolboxActivity`：点击工具外链，失败有 Toast（已加返回值检查）
- `HdSettingsFragment`：HD 横屏设置页外链正常打开
- `LauncherAccountFragment`：账户页外链正常打开，失败有 Toast `social_cannot_open_link`

**阻断条件**：外链无法打开、失败无提示。

##### 10. 分层回归（P2）

**测试场景**：验证 `com.core` 不反向依赖 `com.apps`

```bash
# 命令验证（应 0 命中）
grep -rn "import com.apps" app/src/main/java/com/core/ --include="*.kt" --include="*.java"
```

**实机验证**：
- 冷启动 App：无 `LauncherApplication` 警告日志
- 进入引擎壳层（启动 Kirikiri/Tyrano 游戏）：`LauncherUiBridge` 桥接正常，主题色注入成功
- 主题风格切换（default/rinne/anri/xinhaitian）：粒子颜色按主题重新着色，无重建页面

**阻断条件**：`com.core` 反向依赖 `com.apps`、引擎启动失败、主题切换粒子颜色异常。

##### 测试优先级与建议顺序

1. **P0 必测**：1（PadGameFragment 菜单）→ 2（EdgeToEdge 13 处）→ 3（外链打开）
2. **P1 重要**：4（HD 更新弹窗）→ 5（import + dp 清理）→ 6（Pad 弹窗全集）→ 7（子弹窗宽度）→ 8（异常 + 守卫）
3. **P2 回归**：9（UrlOpener 推广）→ 10（分层回归）

**通过标准**：P0 全部通过 + P1 无阻断 + P2 无新增异常。logcat 无 `FATAL EXCEPTION`、无 `ClassCastException`、无 `IllegalStateException: binding` 类崩溃。

---

## 四、执行原则

1. **渐进式**：每阶段独立可交付；阶段 3/4/5 可按域并行
2. **存量优先**：Java 存量文件「在原文件内继续修改」，只在相关功能改动时迁移 Kotlin（§2）
3. **零行为变更**：拆分/迁移保持对外 API 签名不变，`@JvmStatic`/`@JvmField`/`const val` 兼容
4. **验证基线**：每阶段完成后
   ```bash
   ! grep -r "import com.apps" app/src/main/java/com/core/   # 分层保持
   ./gradlew :app:assembleDebug
   git diff --check
   ```
5. **不强制重写**：禁止为满足格式要求进行无业务价值的大规模重写（§8）

## 五、合规亮点（保持不破坏）

- §8 分层架构：`com.core`→`com.apps` 反向依赖清零；`LauncherUiBridge` 桥接 + `com.core.agent.{net,runtime,store,workspace}` + `com.core.userdata` 包结构清晰
- §8 异常处理：Phase 1 收窄 8 处 catch(Throwable)，新增 catch 块均走 DevLogger.w
- §8 互操作：`@JvmStatic`/`@JvmName`/`@JvmField` 注解完整；`showConfirm`/`showMessageActionChoices` 显式重载保证 Java 调用方兼容
- §5 弹窗工厂：home/ + data/ + game/Library + account + chat(clearConfirm) 已清零手工弹窗；ManageHost 接口整改无断链
- §4 首页风格切换（HomeStyle 枚举/Factory 唯一映射/扩展点覆盖）
- §6 Pad 弹窗区分（showConfirm 288dp 水平 vs showStandardConfirm 270dp 垂直）
- §6 HD 缩放器隔离（所有 HdFragment `usePortraitScaler()=false`）
- 安全防护（WebView 加固/路径穿越防护/AndroidKeyStore 加密/API key 脱敏/McpServerStore HTTPS 强制 + loopback 例外/LauncherUrlOpener scheme 白名单）
- 协程结构化（无 GlobalScope/viewModelScope+Dispatchers.IO/正确重抛 CancellationException）
- ViewBinding 生命周期（onDestroyView 解除 listener 并置空 binding）
- 三语资源机制（strings.xml + strings_core_ui.xml + strings_social.xml）

---

## 六、建议执行顺序

1. **阶段 5.3**（删 LauncherGameActionController）：一次性砍 558 行 + 连带消除 5 处 catch(Throwable) + 5 处 runOnUiThread 未守卫
2. **阶段 3**（异常 + 守卫收尾）：批量收窄，game 域优先（删 5.3 后剩余 42 处 catch + 25 处 runOnUiThread）；含 9.7 CancellationException + 9.8 文件树遍历 + 9.11 守卫清单校准
3. **阶段 8.1**（13 处 configureEdgeToEdgeWindow 补迁移）：含 2 处 luminance 扩展
4. **阶段 4**（弹窗清零）：chat/AvatarCrop/LauncherLaunchTargetPicker 手拼弹窗；含 9.10 ViewHolder dp()
5. **阶段 6**（HD/Pad 一致性 + 取色统一）：视觉一致性；含 9.4 nav 取色合并 + 9.9 HD LocalActivityManager 迁移
6. **阶段 5.1/5.2**（大文件 + IO 下沉）：渐进，随相关功能改动
7. **阶段 9.1/9.2/9.3**（语言约束 + fun interface + const val 下沉）：随相关文件改动迁 Kotlin
8. **阶段 9.5/9.6/9.12**（core 架构违规 + 监听器释放 + 跨模块常量）：core 侧独立技术债阶段
9. **阶段 7 + 8.2/8.3**（硬编码 + 死代码 + 可读性 + 跨模块常量）：收尾
