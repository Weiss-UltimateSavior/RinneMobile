package com.apps.PadUi

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apps.LauncherPreferences
import com.apps.game.DiagnosticsController
import com.apps.game.ManageHost
import com.apps.game.ScanDirectoryController
import com.apps.game.Xp3TargetResolver
import com.apps.theme.LauncherTheme
import com.core.util.RxMainQueue

/**
 * Pad 游戏页的无界面扫描协调器。
 *
 * 复用竖屏管理页的扫描目录、扫描深度和 XP3 入口解析流程；该 Fragment 仅持有
 * Activity Result 注册与生命周期状态，因此必须由 [PadGameModeActivity] 附加后调用。
 */
class PadGameScanFragment : Fragment(), ManageHost {
    private val mainQueue = RxMainQueue()
    private var importInProgress = false
    private lateinit var scanDirectoryController: ScanDirectoryController
    private lateinit var xp3TargetResolver: Xp3TargetResolver
    private lateinit var diagnosticsController: DiagnosticsController

    private val scanDirectoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && ::scanDirectoryController.isInitialized) {
            scanDirectoryController.persistAndSaveScanDirectory(uri)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        xp3TargetResolver = Xp3TargetResolver(this)
        diagnosticsController = DiagnosticsController(this)
        scanDirectoryController = ScanDirectoryController(
            this,
            scanDirectoryPicker,
            xp3TargetResolver::executeScan,
            null,
            null,
        )
    }

    /** Opens the same scan-depth flow used by the portrait management page. */
    fun startScan() {
        if (!isAdded || !::scanDirectoryController.isInitialized) return
        scanDirectoryController.scanConfiguredDirectories()
    }

    /** Opens the portrait management page's diagnostics privacy and action flow. */
    fun showDiagnostics() {
        if (!isAdded || !::diagnosticsController.isInitialized) return
        diagnosticsController.showDiagnosticsPrivacyDialog()
    }

    override fun onDestroy() {
        if (::xp3TargetResolver.isInitialized) {
            xp3TargetResolver.cleanup()
        }
        super.onDestroy()
    }

    override fun getAppContext(): Context = requireContext().applicationContext

    override fun getMainQueue(): RxMainQueue = mainQueue

    override fun getPrefs(): SharedPreferences = requireContext().getSharedPreferences(
        LauncherPreferences.APP_PREFS,
        Context.MODE_PRIVATE,
    )

    override fun dp(value: Int): Int = LauncherTheme.dp(requireContext(), value)

    override fun setResponsiveTextSize(view: TextView, baseSp: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp)
    }

    override fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        onConfirm: Runnable,
    ) {
        PadDialogFactory.showStandardConfirm(requireContext(), title, message, confirmText, onConfirm)
    }

    override fun isImportInProgress(): Boolean = importInProgress

    override fun setImportInProgress(inProgress: Boolean) {
        importInProgress = inProgress
    }
}
