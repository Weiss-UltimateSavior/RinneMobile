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

#### 5.1 大文件拆分（>500 行，按行数降序，共 16 个）

| 文件 | 当前行数 | 拆分方案 |
|------|------|----------|
| game/LauncherLibraryFragment.kt | 1139 | 拆 LibraryToolbarUi / LibrarySwipeGesture / LibraryPagingHelper |
| PadUi/PadSettingsActivity.java | 867 | 按 Section 拆 Theme/Engine/Metadata/Account Controller |
| theme/LauncherDialogFactory.kt | 805 | 拆 Confirm/Choice/Loading/Update 子 object |
| PadUi/PadManageFragment.kt | 752 | 拆 SearchCategory / SyncDelegate / GameActions |
| home/LauncherHomeFragment.kt | 698 | 抽 LauncherAvatarController + LauncherRecentListRenderer |
| profile/LauncherProfileFragment.java | 665 | 抽 ProfileRankFetcher + ProfileImageSync |
| theme/LauncherTheme.kt | 653 | 拆 Colors/Drawables/Switch/Spinner 子 object |
| LauncherActivity.kt | 646 | companion 委托层评估收窄（已 -30 行） |
| PadUi/PadGameFragment.java | 605 | 拆 PadGamePagingController / PadGameAvatarRenderer |
| theme/LauncherParticleView.java | 604 | 按 style 拆 ParticleStyleStrategy |
| widget/AvatarCropActivity.java | 592 | 抽 AvatarCropView + AvatarBitmapDecoder |
| **game/LauncherGameActionController.java** | **558** | **整体删除（见 5.3）** |
| agent/LocalAgentActivity.java | 545 | 抽 LocalAgentCallback 命名类 |
| game/LauncherAddGameActivity.java | 522 | 图片 IO 下沉（随 5.2） |
| game/GameActionMenuFactory.kt | 520 | 抽 EditPlayTimeDialog 后降至 ~300 |
| game/LauncherGameEditActivity.java | 500 | 图片 IO 下沉（随 5.2） |

#### 5.2 UI 层文件 IO 下沉

| 文件 | 位置 | 方案 |
|------|------|------|
| LauncherHomeFragment | 506-568 copyAvatarToInternal | 抽 LauncherAvatarPersistence |
| AvatarCropActivity | 197-224 onConfirm | 抽 AvatarCropOutputWriter |
| LauncherAddGameActivity | 498-531 copyCoverToInternalStorage | 改用 `LauncherScanBridge.copyCoverToInternalStorage` |
| PinnedGameShortcut | 101-135 decodeShortcutBitmap | 主线程解码 ⚠️，改 IO 线程缓存 |
| LauncherProfileFragment | 620-631 isReadableImageUri | 主线程 openInputStream ⚠️，移到 AppExecutors.runOnIo |
| LauncherProfileFragment | 476-533 copyImageToInternal | 提 LauncherImageBridge.copyToInternal |

#### 5.3 重复类整体删除（**未执行，原计划高估已完成**）

- **`game/LauncherGameActionController.java`（558 行）** 仍存在，与 `GameActionMenuFactory.kt` 95% 重复，仅 `PadGameFragment:161` 一处调用。让 PadGameFragment 改用 `GameActionMenuFactory.showGameActionMenu`（`ActionMenuConfig.includeEditAction=false` 已支持），删除该类。一次性消除 558 行 + 5 处 catch(Throwable) + 5 处 runOnUiThread 未守卫。

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
| LocalAgentActivity.java | 494 | — |
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

| 位置 | 问题 |
|---|---|
| `LocalAgentMessageAdapter.kt:105-107` | ViewHolder 内 `dp()`，改 `LauncherTheme.dp(itemView.context, ...)` |
| `LauncherAiChatActivity:114/206` | 手拼 PopupWindow + Dialog（与 4.1/4.2 合并处理） |
| `LauncherSyncCenterActivity:209`、`LocalBackupController:91` | `throw new Exception` → `throw IOException` |
| `EngineOptionCatalog`/`EnginePackageResolver` | Java 存量，迁 Kotlin `object` + `@JvmStatic` |

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
