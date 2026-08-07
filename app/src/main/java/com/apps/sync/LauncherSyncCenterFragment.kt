package com.apps.sync

import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apps.HDModel.HdModeActivity
import com.apps.HDModel.LauncherDialogRouter
import com.apps.common.LauncherInsetsHelper
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherTabletPortraitScaler
import com.core.CoreBackup
import com.core.R
import com.core.databinding.ActivityLauncherSyncCenterBinding
import com.core.launcherbridge.LauncherSyncBridge
import com.core.util.AppExecutors
import java.io.IOException
import java.io.OutputStream

/**
 * 同步中心页（重构计划 9.9 阶段 110 自 LauncherSyncCenterActivity 抽取）。
 *
 * 竖屏由 [LauncherSyncCenterActivity] 薄宿主承载，HD 由
 * [com.apps.HDModel.HdManageFragment] 作为子 Fragment 承载。
 */
class LauncherSyncCenterFragment : Fragment() {
    private var binding: ActivityLauncherSyncCenterBinding? = null

    /**
     * 导入防重复触发标志：与 LauncherManageFragment 保持一致，
     * 避免极端时序下用户连续点击触发并发导入。
     */
    private var importInProgress = false

    private val backupCreateLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) exportLocalBackup(uri)
        }
    private val backupOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLocalBackup(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val currentBinding = ActivityLauncherSyncCenterBinding.inflate(inflater, container, false)
        binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (activity !is HdModeActivity) LauncherTabletPortraitScaler.apply(view)
        val currentBinding = binding ?: return
        LauncherInsetsHelper.applyTopInset(currentBinding.root, currentBinding.syncScroll)
        bindActions()
        applyThemeTone()
        loadConfig()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindActions() {
        val currentBinding = binding ?: return
        currentBinding.btnSave.setOnClickListener { saveConfig() }
        currentBinding.btnTest.setOnClickListener { testConnection() }
        currentBinding.btnSyncNow.setOnClickListener { syncNow() }
        currentBinding.btnExport.setOnClickListener {
            backupCreateLauncher.launch(BACKUP_FILE_PREFIX + System.currentTimeMillis() + ".ykbak")
        }
        currentBinding.btnImport.setOnClickListener { showImportConfirmDialog() }
    }

    private fun applyThemeTone() {
        val currentBinding = binding ?: return
        LauncherTheme.styleMaterialSwitch(currentBinding.syncAutoSwitch)
        LauncherTheme.applyPrimaryTone(requireView())
        LauncherTheme.formInputs(
            currentBinding.syncServerInput,
            currentBinding.syncUserInput,
            currentBinding.syncPasswordInput,
        )
        LauncherTheme.shortActionButton(currentBinding.btnTest)
        LauncherTheme.shortActionButton(currentBinding.btnSyncNow)
        LauncherTheme.longActionButton(currentBinding.btnSave)
        LauncherTheme.shortActionButton(currentBinding.btnExport)
        LauncherTheme.shortActionButton(currentBinding.btnImport)
    }

    private fun loadConfig() {
        val currentBinding = binding ?: return
        val config = LauncherSyncBridge.getConfig(requireContext())
        currentBinding.syncServerInput.setText(config.serverUrl)
        currentBinding.syncUserInput.setText(config.username)
        currentBinding.syncPasswordInput.setText(config.password)
        currentBinding.syncAutoSwitch.isChecked = config.autoSync
        renderStatus()
    }

    private fun renderStatus() {
        val currentBinding = binding ?: return
        val configured = LauncherSyncBridge.isConfigured(requireContext())
        val last = LauncherSyncBridge.lastSyncTime(requireContext())
        val sb = StringBuilder()
        sb.append(
            getString(
                R.string.sync_game_status,
                getString(if (configured) R.string.sync_configured else R.string.sync_not_configured),
            ),
        )
        if (configured) {
            sb.append("\n").append(
                getString(
                    R.string.sync_last_sync,
                    if (last > 0) DateFormat.format("yyyy-MM-dd HH:mm", last)
                    else getString(R.string.sync_never_synced),
                ),
            )
            if (LauncherSyncBridge.isAutoSyncEnabled(requireContext())) {
                sb.append(getString(R.string.sync_auto_enabled_suffix))
            }
        }
        currentBinding.syncStatusText.text = sb.toString()
    }

    private fun saveConfig() {
        val currentBinding = binding ?: return
        val url = currentBinding.syncServerInput.text?.toString()?.trim().orEmpty()
        val user = currentBinding.syncUserInput.text?.toString()?.trim().orEmpty()
        val pass = currentBinding.syncPasswordInput.text?.toString().orEmpty()
        val auto = currentBinding.syncAutoSwitch.isChecked
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(requireContext(), R.string.sync_complete_configuration_required, Toast.LENGTH_SHORT).show()
            return
        }
        LauncherSyncBridge.saveConfig(requireContext(), url, user, pass, auto)
        Toast.makeText(requireContext(), R.string.sync_configuration_saved, Toast.LENGTH_SHORT).show()
        renderStatus()
    }

    private fun testConnection() {
        val currentBinding = binding ?: return
        val url = currentBinding.syncServerInput.text?.toString()?.trim().orEmpty()
        val user = currentBinding.syncUserInput.text?.toString()?.trim().orEmpty()
        val pass = currentBinding.syncPasswordInput.text?.toString().orEmpty()
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(requireContext(), R.string.sync_complete_configuration_required, Toast.LENGTH_SHORT).show()
            return
        }
        // 先临时保存再测试，避免 Bridge 内部使用旧配置
        LauncherSyncBridge.saveConfig(requireContext(), url, user, pass, currentBinding.syncAutoSwitch.isChecked)
        Toast.makeText(requireContext(), R.string.sync_testing_connection, Toast.LENGTH_SHORT).show()
        // IO 线程入队前缓存 applicationContext，避免 Fragment detach 后 requireContext() 崩溃。
        val appContext = requireContext().applicationContext
        AppExecutors.runOnSingle {
            val ok = LauncherSyncBridge.testConnection(appContext)
            activity?.runOnUiThread {
                if (!isAdded || binding == null) return@runOnUiThread
                Toast.makeText(
                    requireContext(),
                    if (ok) R.string.sync_connection_success else R.string.sync_connection_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                renderStatus()
            }
        }
    }

    private fun syncNow() {
        val currentBinding = binding ?: return
        if (!LauncherSyncBridge.isConfigured(requireContext())) {
            Toast.makeText(requireContext(), R.string.sync_save_webdav_first, Toast.LENGTH_SHORT).show()
            return
        }
        // 保存当前输入的配置再同步
        val url = currentBinding.syncServerInput.text?.toString()?.trim().orEmpty()
        val user = currentBinding.syncUserInput.text?.toString()?.trim().orEmpty()
        val pass = currentBinding.syncPasswordInput.text?.toString().orEmpty()
        LauncherSyncBridge.saveConfig(
            requireContext(),
            url,
            user,
            pass,
            currentBinding.syncAutoSwitch.isChecked,
        )

        Toast.makeText(requireContext(), R.string.sync_in_progress, Toast.LENGTH_SHORT).show()
        LauncherSyncBridge.syncNow(
            requireContext(),
            object : LauncherSyncBridge.Callback {
                override fun onStart() {}
                override fun onProgress(item: String, changed: Boolean) {}
                override fun onComplete(message: String) {
                    activity?.runOnUiThread {
                        if (!isAdded || binding == null) return@runOnUiThread
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        renderStatus()
                    }
                }

                override fun onError(error: String) {
                    activity?.runOnUiThread {
                        if (!isAdded || binding == null) return@runOnUiThread
                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    private fun showImportConfirmDialog() {
        LauncherDialogRouter.showLongMessageConfirm(
            requireContext(),
            getString(R.string.sync_local_import_title),
            getString(R.string.sync_local_import_message),
            getString(R.string.sync_choose_file),
        ) {
            backupOpenLauncher.launch(arrayOf("application/octet-stream", "application/json", "text/*", "*/*"))
        }
    }

    private fun exportLocalBackup(uri: Uri) {
        Toast.makeText(requireContext(), R.string.sync_exporting_backup, Toast.LENGTH_SHORT).show()
        // IO 线程入队前缓存 applicationContext，避免 Fragment detach 后 requireContext() 崩溃。
        val appContext = requireContext().applicationContext
        AppExecutors.runOnSingle {
            try {
                val backup = LauncherSyncBridge.exportLocalBackupAsGzip(appContext)
                val out: OutputStream = appContext.contentResolver.openOutputStream(uri)
                    ?: throw IOException("openOutputStream failed")
                out.use {
                    it.write(backup.bytes)
                    it.flush()
                }
                activity?.runOnUiThread {
                    if (!isAdded || binding == null) return@runOnUiThread
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.sync_backup_completed,
                            backup.bytes.size / 1024,
                            backup.originalSize / 1024,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error
            } catch (error: Exception) {
                Log.e("LauncherSync", "export backup failed", error)
                activity?.runOnUiThread {
                    if (!isAdded || binding == null) return@runOnUiThread
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.sync_backup_failed, error.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun importLocalBackup(uri: Uri) {
        if (importInProgress) return
        importInProgress = true
        Toast.makeText(requireContext(), R.string.sync_importing_backup, Toast.LENGTH_SHORT).show()
        // IO 线程入队前缓存 applicationContext，避免 Fragment detach 后 requireContext() 崩溃。
        val appContext = requireContext().applicationContext
        AppExecutors.runOnSingle {
            try {
                LauncherSyncBridge.importLocalBackupFromUri(appContext, uri)
                activity?.runOnUiThread {
                    if (!isAdded || binding == null) return@runOnUiThread
                    importInProgress = false
                    Toast.makeText(requireContext(), R.string.sync_import_completed, Toast.LENGTH_LONG).show()
                }
            } catch (error: Error) {
                // OOM/VirtualMachineError 必须传播，避免在已损坏的 JVM 状态下继续运行
                throw error
            } catch (error: Exception) {
                Log.e("LauncherSync", "import backup failed", error)
                activity?.runOnUiThread {
                    if (!isAdded || binding == null) return@runOnUiThread
                    importInProgress = false
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.sync_import_failed, error.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        /**
         * 本地备份文件名前缀：机器可读值，单源在 com.core.CoreBackup.FILE_PREFIX，
         * 不放入可翻译 string 资源以免被翻译污染文件名（yukihub_backup_ + 时间戳 + .ykbak）
         */
        private val BACKUP_FILE_PREFIX = CoreBackup.FILE_PREFIX
    }
}
