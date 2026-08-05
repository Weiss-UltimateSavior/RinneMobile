package com.apps.game

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdEmbeddedActivityOwner
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.R
import com.core.databinding.ActivityLauncherAddGameBinding
import com.core.launcher.EnginePackages
import com.core.launcherbridge.LauncherGameHubShortcutBridge
import com.core.model.EngineType
import com.core.util.AppExecutors
import com.core.util.DevLogger
import rikka.shizuku.Shizuku

/**
 * 添加游戏页（重构计划 9.9 阶段 110 自 LauncherAddGameActivity 抽取）。
 *
 * 竖屏由 [LauncherAddGameActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdManageFragment] 作为子 Fragment 承载。
 */
class LauncherAddGameFragment : Fragment() {
    private var binding: ActivityLauncherAddGameBinding? = null
    private var launchTargetName = ""
    private var selectedEngineOption: EngineOption? = null
    private var engineOptions: Array<EngineOption> = emptyArray()

    private var gameDirUri: Uri? = null
    private var coverUri: Uri? = null
    private var lastEngineDefaultPackage = ""
    /** 标记游戏目录 SAF 持久化授权降级为只读或彻底失败，便于 saveGame 时给用户提示。 */
    private var directoryPermissionDegraded = false
    /** 标记封面 SAF 持久化授权降级为只读或彻底失败（封面只需读，不影响写入）。 */
    private var coverPermissionDegraded = false

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_GAMEHUB_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
            if (!isAdded) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                importGameHubShortcutFromShizuku()
            } else {
                Toast.makeText(requireContext(), R.string.game_shizuku_manual_id, Toast.LENGTH_LONG).show()
            }
        }

    private val directoryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            directoryPermissionDegraded = !persistUriPermission(uri)
            gameDirUri = uri
            binding?.addGameDirText?.setText(displayUri(uri))
            fillTitleFromDirIfEmpty(uri)
            launchTargetName = ""
            binding?.addGameLaunchTargetInput?.setText(R.string.game_launch_file_select)
        }

    private val coverPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            coverPermissionDegraded = !persistUriPermission(uri)
            coverUri = uri
            binding?.addGameCoverText?.setText(displayUri(uri))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (error: Exception) {
            DevLogger.w("LauncherAddGame", "Failed to register Shizuku permission listener", error)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherAddGameBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        engineOptions = EngineOptionCatalog.create(requireContext(), false)
        LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        bindViews()
        restoreTransientState(savedInstanceState)
        applySystemBarInsets()
        setupEnginePicker()
        bindActions()
        applyThemeTone()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ENGINE_OPTION_INDEX, selectedEngineOptionIndex())
        outState.putString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, lastEngineDefaultPackage)
        gameDirUri?.let { outState.putString(STATE_GAME_DIRECTORY_URI, it.toString()) }
        coverUri?.let { outState.putString(STATE_COVER_URI, it.toString()) }
        outState.putString(STATE_LAUNCH_TARGET, launchTargetName)
        outState.putBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, directoryPermissionDegraded)
        outState.putBoolean(STATE_COVER_PERMISSION_DEGRADED, coverPermissionDegraded)
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (error: Exception) {
            DevLogger.w("LauncherAddGame", "Failed to remove Shizuku permission listener", error)
        }
        super.onDestroy()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindViews() {
        val currentBinding = binding ?: return
        selectedEngineOption = engineOptions[0]
        currentBinding.addGameEngineText.text = selectedEngineOption?.label
    }

    private fun restoreTransientState(savedInstanceState: Bundle?) {
        val currentBinding = binding ?: return
        if (savedInstanceState == null) return
        selectedEngineOption = engineOptions[boundedEngineOptionIndex(
            savedInstanceState.getInt(STATE_ENGINE_OPTION_INDEX, 0),
        )]
        currentBinding.addGameEngineText.text = selectedEngineOption?.label
        lastEngineDefaultPackage = savedInstanceState.getString(STATE_LAST_ENGINE_DEFAULT_PACKAGE, "")
        gameDirUri = uriFromState(savedInstanceState.getString(STATE_GAME_DIRECTORY_URI))
        if (gameDirUri != null) currentBinding.addGameDirText.text = displayUri(gameDirUri)
        coverUri = uriFromState(savedInstanceState.getString(STATE_COVER_URI))
        if (coverUri != null) currentBinding.addGameCoverText.text = displayUri(coverUri)
        launchTargetName = savedInstanceState.getString(STATE_LAUNCH_TARGET, "")
        if (launchTargetName.isNotEmpty()) {
            currentBinding.addGameLaunchTargetInput.setText(launchTargetName)
        }
        directoryPermissionDegraded = savedInstanceState.getBoolean(STATE_DIRECTORY_PERMISSION_DEGRADED, false)
        coverPermissionDegraded = savedInstanceState.getBoolean(STATE_COVER_PERMISSION_DEGRADED, false)
    }

    private fun uriFromState(value: String?): Uri? {
        if (value == null || value.trim().isEmpty()) return null
        return try {
            Uri.parse(value)
        } catch (error: IllegalArgumentException) {
            DevLogger.w("LauncherAddGame", "Invalid saved URI state", error)
            null
        }
    }

    private fun applySystemBarInsets() {
        val currentBinding = binding ?: return
        val originalLeft = currentBinding.addGameScroll.paddingLeft
        val originalTop = currentBinding.addGameScroll.paddingTop
        val originalRight = currentBinding.addGameScroll.paddingRight
        val originalBottom = currentBinding.addGameScroll.paddingBottom
        currentBinding.addGameScroll.setOnApplyWindowInsetsListener { _, insets ->
            currentBinding.addGameScroll.setPadding(
                originalLeft,
                originalTop + insets.systemWindowInsetTop,
                originalRight,
                originalBottom,
            )
            insets
        }
        currentBinding.addGameScroll.requestApplyInsets()
    }

    private fun setupEnginePicker() {
        val currentBinding = binding ?: return
        currentBinding.addGameEngineText.setOnClickListener {
            val labels = Array<CharSequence>(engineOptions.size) { engineOptions[it].label }
            var checked = 0
            for (i in engineOptions.indices) {
                if (engineOptions[i] == selectedEngineOption) {
                    checked = i
                    break
                }
            }
            LauncherDialogRouter.showSingleChoice(
                requireContext(),
                getString(R.string.game_select_engine_title),
                labels,
                checked,
            ) { index ->
                applyEngineSelection(index)
            }
        }
    }

    private fun applyEngineSelection(index: Int) {
        val currentBinding = binding ?: return
        selectedEngineOption = engineOptions[boundedEngineOptionIndex(index)]
        currentBinding.addGameEngineText.text = selectedEngineOption?.label
        // 切换引擎时无条件重置为该引擎的默认包名，覆盖用户手动输入或列表选择的值。
        val nextDefault = EnginePackageResolver.forOption(selectedEngineOption)
        currentBinding.addGameEmulatorInput.setText(nextDefault)
        lastEngineDefaultPackage = nextDefault
    }

    private fun selectedEngineOptionIndex(): Int {
        for (i in engineOptions.indices) {
            if (engineOptions[i] == selectedEngineOption) return i
        }
        return 0
    }

    private fun boundedEngineOptionIndex(index: Int): Int =
        if (index in engineOptions.indices) index else 0

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.addGameDirText.setOnClickListener { directoryPicker.launch(null) }
        currentBinding.addGameLaunchTargetInput.setOnClickListener { showLaunchTargetPicker() }
        // 模拟器包名支持手动输入；右侧图标点击从应用列表选择。
        currentBinding.addGamePickEmulatorApp.setOnClickListener {
            showAppPicker(currentBinding.addGameEmulatorInput)
        }
        currentBinding.addGameImportGameHubShortcut.setOnClickListener { importGameHubShortcutFromShizuku() }
        currentBinding.addGameCoverText.setOnClickListener {
            coverPicker.launch(arrayOf("image/*"))
        }
        currentBinding.addGameSave.setOnClickListener { saveGame() }
    }

    private fun applyThemeTone() {
        val currentBinding = binding ?: return
        LauncherTheme.longActionButton(currentBinding.addGameSave)
        LauncherTheme.applyPrimaryTone(requireView())
        LauncherTheme.formInputs(
            currentBinding.addGameNameInput,
            currentBinding.addGameEmulatorInput,
            currentBinding.addGameGameHubIdInput,
            currentBinding.addGameDescriptionInput,
        )
        currentBinding.addGamePickEmulatorApp.setImageTintList(
            ColorStateList.valueOf(LauncherTheme.primary(requireContext())),
        )
        currentBinding.addGameImportGameHubShortcut.setImageTintList(
            ColorStateList.valueOf(LauncherTheme.primary(requireContext())),
        )
    }

    private fun importGameHubShortcutFromShizuku() {
        val currentBinding = binding ?: return
        if (selectedEngine() != EngineType.GAMEHUB) {
            Toast.makeText(requireContext(), R.string.game_select_gamehub_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!LauncherGameHubShortcutBridge.isShizukuRunning()) {
            Toast.makeText(requireContext(), R.string.game_shizuku_start_first, Toast.LENGTH_LONG).show()
            return
        }
        if (!LauncherGameHubShortcutBridge.hasShizukuPermission()) {
            try {
                LauncherGameHubShortcutBridge.requestShizukuPermission(SHIZUKU_GAMEHUB_PERMISSION_REQUEST)
            } catch (error: Exception) {
                DevLogger.w("LauncherAddGame", "Failed to request Shizuku permission", error)
                Toast.makeText(requireContext(), R.string.game_shizuku_request_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        currentBinding.addGameImportGameHubShortcut.isEnabled = false
        currentBinding.addGameImportGameHubShortcut.alpha = 0.45f
        currentBinding.addGameImportGameHubShortcut.contentDescription =
            getString(R.string.game_gamehub_reading_shortcuts)
        AppExecutors.runOnIo {
            val items: List<LauncherGameHubShortcutBridge.Shortcut> = try {
                LauncherGameHubShortcutBridge.loadShortcuts()
            } catch (error: Exception) {
                DevLogger.w("LauncherAddGame", "Failed to load GameHub shortcuts", error)
                emptyList()
            }
            val shortcuts = items
            activity?.runOnUiThread {
                if (!isAdded || binding == null) return@runOnUiThread
                val current = binding ?: return@runOnUiThread
                current.addGameImportGameHubShortcut.isEnabled = true
                current.addGameImportGameHubShortcut.alpha = 1f
                current.addGameImportGameHubShortcut.contentDescription =
                    getString(R.string.game_gamehub_import_shortcut)
                if (shortcuts.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.game_gamehub_no_shortcuts, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = Array<CharSequence>(shortcuts.size) {
                    shortcuts[it].displayLabel + "\n" + shortcuts[it].localGameId
                }
                LauncherDialogRouter.showActionChoices(
                    requireContext(),
                    getString(R.string.game_gamehub_choose_shortcut),
                    labels,
                ) { which ->
                    applyGameHubShortcut(shortcuts[which])
                }
            }
        }
    }

    private fun applyGameHubShortcut(item: LauncherGameHubShortcutBridge.Shortcut?) {
        val currentBinding = binding ?: return
        if (item == null) return
        currentBinding.addGameGameHubIdInput.setText(item.localGameId)
        if (textOf(currentBinding.addGameNameInput).isEmpty()) {
            currentBinding.addGameNameInput.setText(item.localAppName)
        }
        if (textOf(currentBinding.addGameEmulatorInput).isEmpty()) {
            currentBinding.addGameEmulatorInput.setText(EnginePackages.EXTERNAL_GAMEHUB)
        }
    }

    /** 扫描游戏目录下的相关游戏文件，弹出列表供用户选择启动入口。 */
    private fun showLaunchTargetPicker() {
        val hostActivity = activity as? AppCompatActivity ?: return
        LauncherLaunchTargetPicker.show(hostActivity, gameDirUri, selectedEngine()) { target ->
            launchTargetName = target
            binding?.addGameLaunchTargetInput?.setText(target)
        }
    }

    private fun saveGame() {
        val currentBinding = binding ?: return
        val title = textOf(currentBinding.addGameNameInput)
        if (title.isEmpty()) {
            Toast.makeText(requireContext(), R.string.game_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (gameDirUri == null) {
            Toast.makeText(requireContext(), R.string.game_directory_required, Toast.LENGTH_SHORT).show()
            return
        }

        currentBinding.addGameSave.isEnabled = false
        currentBinding.addGameSave.setText(R.string.game_common_saving)

        val appContext = requireContext().applicationContext
        val selectedGameDir = gameDirUri ?: return
        // UI 线程收集输入后交由 AddGameSavePipeline 在 IO 线程执行（阶段 119 拆分）。
        val input = AddGameSavePipeline.Input(
            title = title,
            engine = selectedEngine(),
            rpgSubtype = selectedRpgMakerSubtype(),
            launchTarget = launchTargetName,
            emulatorInput = textOf(currentBinding.addGameEmulatorInput),
            gameHubId = textOf(currentBinding.addGameGameHubIdInput),
            description = textOf(currentBinding.addGameDescriptionInput),
            gameDir = selectedGameDir,
            cover = coverUri,
        )
        AppExecutors.runOnSingle {
            val id = AddGameSavePipeline.save(appContext, input)
            activity?.runOnUiThread {
                if (!isAdded || binding == null) return@runOnUiThread
                val current = binding ?: return@runOnUiThread
                current.addGameSave.isEnabled = true
                current.addGameSave.setText(R.string.game_common_save)
                if (id > 0) {
                    if (directoryPermissionDegraded) {
                        // 游戏目录 SAF 持久化授权降级为只读或彻底失败，提示用户可能无法写入存档
                        Toast.makeText(
                            requireContext(),
                            R.string.game_added_limited_access,
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(requireContext(), R.string.game_added, Toast.LENGTH_SHORT).show()
                    }
                    requestClose(resultOk = true)
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.game_save_duplicate_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun selectedEngine(): EngineType =
        selectedEngineOption?.engine ?: EngineType.AUTO

    /**
     * 取当前选择的 EngineOption 的子引擎标识（RPG Maker 或 Ren'Py）。
     * 仅当选中的引擎有 subtype 且非空时返回，否则返回空串。
     * 必须在 UI 线程调用（读取选择状态）。
     */
    private fun selectedRpgMakerSubtype(): String =
        EnginePackageResolver.subtypeForOption(selectedEngineOption)

    /** 持久化 URI 授权。返回 true 表示 RW 授权成功，false 表示降级为只读或彻底失败。 */
    private fun persistUriPermission(uri: Uri?): Boolean {
        if (uri == null) return false
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (first: Exception) {
            Log.w("LauncherAddGame", "takePersistableUriPermission(RW) failed, retry RO", first)
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                false
            } catch (e: Exception) {
                Log.w("LauncherAddGame", "takePersistableUriPermission(RO) failed", e)
                false
            }
        }
    }

    private fun fillTitleFromDirIfEmpty(uri: Uri?) {
        val currentBinding = binding ?: return
        if (textOf(currentBinding.addGameNameInput).isNotEmpty() || uri == null) return
        val display = displayUri(uri)
        val slash = display.lastIndexOf('/')
        var title = if (slash >= 0 && slash < display.length - 1) display.substring(slash + 1) else display
        if (title.startsWith("primary:")) title = title.substring("primary:".length)
        if (title.trim().isNotEmpty()) currentBinding.addGameNameInput.setText(title.trim())
    }

    private fun displayUri(uri: Uri?): String {
        if (uri == null) return ""
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (docId != null && docId.trim().isNotEmpty()) return Uri.decode(docId)
        } catch (error: IllegalArgumentException) {
            DevLogger.w("LauncherAddGame", "Failed to read tree document id", error)
        } catch (error: SecurityException) {
            DevLogger.w("LauncherAddGame", "Failed to read tree document id", error)
        }
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId != null && docId.trim().isNotEmpty()) return Uri.decode(docId)
        } catch (error: IllegalArgumentException) {
            DevLogger.w("LauncherAddGame", "Failed to read document id", error)
        } catch (error: SecurityException) {
            DevLogger.w("LauncherAddGame", "Failed to read document id", error)
        }
        return uri.toString()
    }

    private fun textOf(editText: EditText?): String =
        if (editText == null || editText.text == null) "" else editText.text.toString().trim()

    private fun textOf(textView: TextView?): String =
        if (textView == null || textView.text == null) "" else textView.text.toString().trim()

    private fun showAppPicker(target: TextView) {
        val hostActivity = activity as? AppCompatActivity ?: return
        LauncherAppPickerDialog.show(hostActivity) { packageName -> target.text = packageName }
    }

    /** 按承载宿主分派关闭：竖屏薄宿主 finish+setResult，HD 由父 Fragment 关闭子 Fragment。 */
    private fun requestClose(resultOk: Boolean) {
        when (val host = activity) {
            is LauncherAddGameActivity -> host.finishAddGame(resultOk)
            is HdModeActivity -> (parentFragment as? HdEmbeddedActivityOwner)?.closeEmbeddedActivity()
            else -> Unit
        }
    }

    companion object {
        private const val STATE_ENGINE_OPTION_INDEX = "engine_option_index"
        private const val STATE_LAST_ENGINE_DEFAULT_PACKAGE = "last_engine_default_package"
        private const val STATE_GAME_DIRECTORY_URI = "game_directory_uri"
        private const val STATE_COVER_URI = "cover_uri"
        private const val STATE_LAUNCH_TARGET = "launch_target"
        private const val STATE_DIRECTORY_PERMISSION_DEGRADED = "directory_permission_degraded"
        private const val STATE_COVER_PERMISSION_DEGRADED = "cover_permission_degraded"
        private const val SHIZUKU_GAMEHUB_PERMISSION_REQUEST = 62002
    }
}
