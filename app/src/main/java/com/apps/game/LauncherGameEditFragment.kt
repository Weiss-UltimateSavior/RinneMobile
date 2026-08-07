package com.apps.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherGameEditBinding
import com.core.diagnostics.GameDiagnostics
import com.core.launcher.EnginePackages
import com.core.launcherbridge.LauncherGameHubShortcutBridge
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors

/**
 * 编辑游戏页（自 LauncherGameEditActivity 抽取，重构计划 9.9 阶段 110 同构；
 * 阶段 142 拆分 GameEditFormState/GameHubShortcutController/GameDirectoryUriHelper 后 587→~420 行）。
 *
 * 竖屏由 [LauncherGameEditActivity] 薄宿主承载；HD 横屏由
 * [com.apps.HDModel.HdGameLibraryFragment] 压入主容器回退栈（保留左侧导航）。
 * 弹窗统一走 [LauncherDialogRouter] 以匹配 HD/竖屏容器视觉。
 */
class LauncherGameEditFragment : Fragment() {

    private var binding: ActivityLauncherGameEditBinding? = null
    private var currentEngineOption: EngineOption? = null
    private var engineOptions: Array<EngineOption> = emptyArray()

    private var game: Game? = null
    private var originalEngine: EngineType? = null

    /** 表单状态（保存/恢复 + STATE_* 常量，阶段 142 拆分）。 */
    private val formState = GameEditFormState()

    /** GameHub 快捷方式导入子流程（Shizuku，阶段 142 拆分）。 */
    private val gameHubShortcut = GameHubShortcutController(
        this,
        { selectedEngineOption()?.engine == EngineType.GAMEHUB },
        { binding },
        ::applyGameHubShortcut,
    )

    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val currentBinding = binding ?: return@registerForActivityResult
        formState.directoryPermissionDegraded =
            !GameDirectoryUriHelper.persistUriPermission(requireContext(), uri)
        formState.selectedGameDirectoryUri = uri
        formState.directoryRebound = game != null && game?.rootUri != null
            && !game?.rootUri.equals(uri.toString())
        currentBinding.editDir.setText(GameDirectoryUriHelper.displayDirectoryUri(requireContext(), uri))
        currentBinding.editDir.setTextColor(LauncherTheme.primary(requireContext()))
    }

    private val coverPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && result.data?.data != null) {
            formState.selectedCoverUri = result.data?.data
            binding?.editCoverStatus?.setText(R.string.game_cover_selected)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherGameEditBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val currentBinding = binding ?: return
        // HD 主容器内嵌：清除布局自带的不透明背景，露出宿主容器的圆角白卡，与库/存档管理页一致。
        if (activity is HdModeActivity) view.background = null
        engineOptions = EngineOptionCatalog.create(requireContext(), true)
        // 竖屏平板缩放仅对竖屏容器生效；HD 横屏壳内无差别调用（scaleFor 返回 1）语义不符，显式跳过。
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        bindViews()
        restoreTransientState(savedInstanceState)
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.editScroll)
        bindActions()
        applyThemeTone()
        loadGame()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        gameHubShortcut.attach()
    }

    override fun onDetach() {
        gameHubShortcut.detach()
        super.onDetach()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentBinding = binding
        if (currentBinding != null) {
            formState.save(outState, currentBinding, selectedEngineOptionIndex())
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindViews() {
        currentEngineOption = engineOptions[0]
        binding?.editEngineText?.setText(currentEngineOption?.label)
    }

    private fun restoreTransientState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val currentBinding = binding ?: return
        formState.restore(
            savedInstanceState,
            currentBinding,
            engineOptions,
        ) { option ->
            currentEngineOption = option
            currentBinding.editEngineText.setText(option?.label)
        }
        if (formState.restoreDirectorySelection) {
            currentBinding.editDir.setText(
                GameDirectoryUriHelper.displayDirectoryUri(requireContext(), formState.selectedGameDirectoryUri),
            )
            currentBinding.editDir.setTextColor(LauncherTheme.primary(requireContext()))
        }
        if (formState.restoreCoverSelection) currentBinding.editCoverStatus.setText(R.string.game_cover_selected)
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.editEngineText.setOnClickListener { showEnginePicker() }
        currentBinding.btnPickEmulatorApp.setOnClickListener {
            LauncherAppPickerDialog.show(requireActivity() as AppCompatActivity, currentBinding.editEmulator::setText)
        }
        currentBinding.editLaunchTarget.setOnClickListener {
            LauncherLaunchTargetPicker.show(
                requireActivity() as AppCompatActivity,
                formState.selectedGameDirectoryUri,
                selectedEngineOption()?.engine ?: EngineType.UNKNOWN,
                currentBinding.editLaunchTarget::setText,
            )
        }
        currentBinding.btnPickDirectory.setOnClickListener { directoryPicker.launch(formState.selectedGameDirectoryUri) }
        currentBinding.btnPickCover.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.setType("image/*")
            coverPicker.launch(intent)
        }
        currentBinding.btnImportGameHubShortcut.setOnClickListener { gameHubShortcut.importFromShizuku() }
        currentBinding.btnCancel.setOnClickListener { requestClose() }
        currentBinding.btnSave.setOnClickListener { saveGame() }
    }

    private fun loadGame() {
        val gameId = requireArguments().getLong(EXTRA_GAME_ID, -1L)
        if (gameId <= 0) {
            requestClose()
            return
        }
        AppExecutors.io().execute {
            val loaded = LauncherRepositoryBridge.findGameById(requireContext(), gameId)
            val currentBinding = binding ?: return@execute
            currentBinding.root.post {
                if (isUiUnavailable()) return@post
                if (loaded == null) {
                    Toast.makeText(requireContext(), R.string.game_not_found, Toast.LENGTH_SHORT).show()
                    requestClose()
                    return@post
                }
                game = loaded
                originalEngine = loaded.engine
                if (!formState.restoreFormState) {
                    currentBinding.editTitle.setText(loaded.title)
                    currentBinding.editEmulator.setText(loaded.emulatorPackage)
                    currentBinding.editLaunchTarget.setText(loaded.launchTarget)
                    currentBinding.editGameHubLocalGameId.setText(loaded.gamehubLocalGameId)
                    currentBinding.editDescription.setText(loaded.description)
                }
                if (!formState.restoreEngineSelection) {
                    currentEngineOption = EnginePackageResolver.findOption(
                        engineOptions,
                        loaded.engine,
                        loaded.emulatorPackage,
                    )
                    currentBinding.editEngineText.setText(currentEngineOption?.label)
                    formState.lastEngineDefaultPackage = EnginePackageResolver.forOption(currentEngineOption)
                }
                val rootUri = loaded.rootUri
                if (!formState.restoreDirectorySelection && rootUri != null && rootUri.startsWith("content://")) {
                    formState.selectedGameDirectoryUri = Uri.parse(rootUri)
                    currentBinding.editDir.setText(
                        GameDirectoryUriHelper.displayDirectoryUri(requireContext(), formState.selectedGameDirectoryUri),
                    )
                } else if (!formState.restoreDirectorySelection) {
                    currentBinding.editDir.setText(
                        if (rootUri.isNullOrBlank()) {
                            getString(R.string.game_directory_not_selected)
                        } else {
                            rootUri
                        }
                    )
                }
                if (!formState.restoreCoverSelection && !loaded.coverUri.isNullOrBlank()) {
                    currentBinding.editCoverStatus.setText(R.string.game_cover_existing)
                }
            }
        }
    }

    private fun saveGame() {
        val currentBinding = binding ?: return
        val target = game ?: return
        val title = currentBinding.editTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Toast.makeText(requireContext(), R.string.game_title_required, Toast.LENGTH_SHORT).show()
            return
        }
        currentBinding.btnSave.isEnabled = false
        currentBinding.btnSave.setText(R.string.game_common_saving)

        target.title = title
        val opt = selectedEngineOption()
        // Rebinding a directory is independent of engine selection. In particular, do not
        // downgrade an existing detected engine to AUTO after a recreation unless the user
        // deliberately chose another engine.
        target.engine = if (formState.directoryRebound && !formState.engineChanged && originalEngine != null) {
            originalEngine
        } else {
            opt?.engine ?: EngineType.UNKNOWN
        }
        var emuPkg = currentBinding.editEmulator.text?.toString()?.trim().orEmpty()
        // 若用户未手动改 emulatorPackage，根据选中子引擎自动填 internal.<subtype>。
        if (emuPkg.isEmpty() && opt != null
            && (opt.engine == EngineType.RPGMAKER || opt.engine == EngineType.RENPY
                || opt.engine == EngineType.GODOT)
            && !opt.rpgMakerSubtype.isNullOrEmpty()
        ) {
            emuPkg = EnginePackageResolver.internalPackage(opt.rpgMakerSubtype)
        }
        target.emulatorPackage = emuPkg
        target.launchTarget = currentBinding.editLaunchTarget.text?.toString()?.trim().orEmpty()
        // launchTarget 是持久化引擎数据，保持语言无关的哨兵值，内部启动器不接收翻译后的显示标签。
        if (target.launchTarget.isNullOrEmpty()) target.launchTarget = "[游戏目录]"
        val dir = formState.selectedGameDirectoryUri
        if (dir != null) target.rootUri = dir.toString()
        target.gamehubLocalGameId = currentBinding.editGameHubLocalGameId.text?.toString()?.trim().orEmpty()
        target.description = currentBinding.editDescription.text?.toString()?.trim().orEmpty()

        AppExecutors.io().execute {
            try {
                val cover = formState.selectedCoverUri
                if (cover != null) {
                    val storedCover = com.core.launcherbridge.LauncherScanBridge.copyCoverToInternalStorage(
                        requireContext(),
                        cover.toString(),
                    )
                    if (storedCover != null) {
                        target.coverUri = storedCover
                        target.coverPersistUri = storedCover
                        target.coverSourceType = 1
                    }
                }
                val affected = LauncherRepositoryBridge.updateGame(requireContext(), target)
                if (affected <= 0) {
                    throw IllegalStateException(getString(R.string.game_record_write_failed))
                }
                if (formState.directoryRebound) GameDiagnostics.recordDirectoryRebound(requireContext(), target)
                val b = binding ?: return@execute
                b.root.post {
                    if (isUiUnavailable()) return@post
                    if (formState.directoryPermissionDegraded) {
                        Toast.makeText(requireContext(), R.string.game_saved_limited_access, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), R.string.game_saved, Toast.LENGTH_SHORT).show()
                    }
                    requestClose()
                }
            } catch (error: Error) {
                throw error
            } catch (error: Exception) {
                val b = binding ?: return@execute
                b.root.post {
                    if (isUiUnavailable()) return@post
                    b.btnSave.isEnabled = true
                    b.btnSave.setText(R.string.game_common_save)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.game_save_failed_reason, error.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun applyThemeTone() {
        val currentBinding = binding ?: return
        LauncherTheme.applyPrimaryTone(currentBinding.root)
        LauncherTheme.formInputs(
            currentBinding.editTitle,
            currentBinding.editEmulator,
            currentBinding.editLaunchTarget,
            currentBinding.editGameHubLocalGameId,
            currentBinding.editDescription,
        )
        LauncherTheme.longActionButton(currentBinding.btnPickDirectory)
        LauncherTheme.longActionButton(currentBinding.btnPickCover)
        currentBinding.btnPickEmulatorApp.imageTintList =
            ColorStateList.valueOf(LauncherTheme.primary(requireContext()))
        currentBinding.btnImportGameHubShortcut.imageTintList =
            ColorStateList.valueOf(LauncherTheme.primary(requireContext()))
        LauncherTheme.longActionButton(currentBinding.btnSave)
        LauncherTheme.longActionButton(currentBinding.btnCancel)
    }

    private fun applyGameHubShortcut(item: LauncherGameHubShortcutBridge.Shortcut) {
        val currentBinding = binding ?: return
        currentBinding.editGameHubLocalGameId.setText(item.localGameId)
        if (currentBinding.editTitle.text?.toString()?.trim().isNullOrEmpty()) {
            currentBinding.editTitle.setText(item.localAppName)
        }
        if (currentBinding.editEmulator.text?.toString()?.trim().isNullOrEmpty()) {
            currentBinding.editEmulator.setText(EnginePackages.EXTERNAL_GAMEHUB)
        }
    }

    private fun selectedEngineOption(): EngineOption? = currentEngineOption ?: engineOptions.firstOrNull()

    private fun showEnginePicker() {
        val labels = Array<CharSequence>(engineOptions.size) { engineOptions[it].label }
        var checked = 0
        for (i in engineOptions.indices) {
            if (engineOptions[i] == currentEngineOption) {
                checked = i
                break
            }
        }
        LauncherDialogRouter.showSingleChoice(
            requireContext(),
            getString(R.string.game_select_engine_title),
            labels,
            checked,
        ) { index -> applyEngineSelection(index) }
    }

    private fun applyEngineSelection(index: Int) {
        val currentBinding = binding ?: return
        currentEngineOption = engineOptions[boundedEngineOptionIndex(index)]
        formState.engineChanged = true
        currentBinding.editEngineText.setText(currentEngineOption?.label)
        // 切换引擎时无条件重置为该引擎的默认包名，覆盖用户手动输入或列表选择的值。
        val nextDefault = EnginePackageResolver.forOption(currentEngineOption)
        currentBinding.editEmulator.setText(nextDefault)
        formState.lastEngineDefaultPackage = nextDefault
    }

    private fun selectedEngineOptionIndex(): Int {
        for (i in engineOptions.indices) {
            if (engineOptions[i] == currentEngineOption) return i
        }
        return 0
    }

    private fun boundedEngineOptionIndex(index: Int): Int =
        if (index in engineOptions.indices) index else 0

    private fun isUiUnavailable(): Boolean = !isAdded || binding == null

    /** 按承载宿主分派关闭：竖屏薄宿主 finish，HD 主容器回退栈弹栈。 */
    private fun requestClose() {
        when (val host = activity) {
            is LauncherGameEditActivity -> host.finishGameEdit()
            else -> host?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_GAME_ID = "extra_game_id"

        @JvmStatic
        fun newInstance(gameId: Long): LauncherGameEditFragment =
            LauncherGameEditFragment().apply {
                arguments = Bundle().apply { putLong(EXTRA_GAME_ID, gameId) }
            }
    }
}
