package com.apps.game

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.apps.theme.LauncherDialogFactory
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.launcherbridge.LauncherScanBridge
import com.core.prefs.ScanRootKeys

/**
 * 扫描目录管理控制器：从 LauncherManageFragment 抽离的扫描目录增删、启用状态、
 * 扫描深度选择与目录列表渲染逻辑。
 *
 * 依赖 ManageHost 提供 Context、UI 缩放、SharedPreferences 与共享对话框；
 * 通过 OnScanRequestedListener 回调将扫描请求转交 Xp3TargetResolver 等外部组件。
 */
class ScanDirectoryController(
    private val host: ManageHost,
    private val scanDirectoryPicker: ActivityResultLauncher<Uri>,
    private val scanListener: OnScanRequestedListener,
    private val directoryList: ViewGroup?,
    private val directoryEmpty: View?
) {

    /** 扫描请求回调，由外部（如 Xp3TargetResolver）实现。 */
    fun interface OnScanRequestedListener {
        fun onScanRequested(roots: List<String>, depth: Int, fullRefresh: Boolean)
    }

    companion object {
        private const val DEFAULT_SCAN_DEPTH = 2
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCAN_ROOTS = 3
    }

    fun confirmAddDirectory() {
        host.showConfirmDialog(
            host.getString(R.string.game_scan_add_directory),
            host.getString(R.string.game_scan_add_directory_message),
            host.getString(R.string.game_scan_add)
        ) {
            scanDirectoryPicker.launch(null)
        }
    }

    fun persistAndSaveScanDirectory(uri: Uri) {
        try {
            host.requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (ignored: SecurityException) {
            // Some providers do not grant persistable permissions, but may still be readable.
        }

        val roots = getScanRootUris().toMutableList()
        val value = uri.toString()
        roots.remove(value)
        if (roots.size >= MAX_SCAN_ROOTS) {
            Toast.makeText(
                host.requireContext(),
                host.getString(R.string.game_scan_root_limit, MAX_SCAN_ROOTS),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        roots.add(value)
        saveScanRootUris(roots)
        renderScanDirectories()
        Toast.makeText(host.requireContext(), R.string.game_scan_added, Toast.LENGTH_SHORT).show()
        showScanDepthDialog(listOf(value))
    }

    fun scanConfiguredDirectories() {
        val roots = getActiveScanRootUris()
        if (roots.isEmpty()) {
            val message = host.getString(
                if (getScanRootUris().isEmpty()) R.string.game_scan_add_first else R.string.game_scan_enable_first
            )
            Toast.makeText(host.requireContext(), message, Toast.LENGTH_SHORT).show()
            return
        }
        showScanDepthDialog(roots)
    }

    fun showScanDepthDialog(roots: List<String>) {
        val depthLabels: Array<CharSequence> = arrayOf(
            host.getString(R.string.game_scan_shallow),
            host.getString(R.string.game_scan_standard),
            host.getString(R.string.game_scan_deep),
            host.getString(R.string.game_scan_deeper),
            host.getString(R.string.game_scan_all),
            host.getString(R.string.game_scan_recursive)
        )
        val currentDepth = scanDepth()
        val depthValues = intArrayOf(
            1, 2, 3, 4, LauncherScanBridge.SCAN_ALL_LEVELS, LauncherScanBridge.SCAN_UNTIL_GAME_MATCH
        )

        LauncherDialogFactory.showScanDepthChoices(
            host.requireContext(),
            host.getString(R.string.game_scan_title),
            host.getString(R.string.game_scan_mode_quick),
            host.getString(R.string.game_scan_mode_full),
            depthLabels,
            depthValues,
            currentDepth
        ) { depth, fullRefresh ->
            saveScanDepth(depth)
            scanListener.onScanRequested(roots, depth, fullRefresh)
        }
    }

    fun saveScanDepth(depth: Int) {
        host.prefs.edit().putInt(ScanRootKeys.KEY_STARTUP_SCAN_DEPTH, depth).apply()
    }

    fun renderScanDirectories() {
        if (directoryList == null || directoryEmpty == null) return
        val roots = getScanRootUris()
        val states = getScanRootEnabledStates()
        directoryList.removeAllViews()
        directoryEmpty.visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE
        directoryList.visibility = if (roots.isEmpty()) View.GONE else View.VISIBLE
        for (i in roots.indices) {
            directoryList.addView(createDirectoryRow(roots[i], i, i >= states.size || states[i]))
        }
    }

    private fun createDirectoryRow(root: String, index: Int, enabled: Boolean): View {
        val row = LinearLayout(host.requireContext())
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(host.dp(13), 0, host.dp(9), 0)
        row.setBackgroundResource(R.drawable.launcher_white_card)

        val directoryIcon = ImageView(host.requireContext())
        directoryIcon.setImageResource(R.drawable.launcher_manage_scan_directory_icon)
        directoryIcon.imageTintList = ColorStateList.valueOf(LauncherTheme.primary(host.requireContext()))
        directoryIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        row.addView(directoryIcon, LinearLayout.LayoutParams(host.dp(25), host.dp(25)))

        val title = TextView(host.requireContext())
        title.text = directoryLabel(root)
        title.setTextColor(LauncherTheme.text(host.requireContext()))
        host.setResponsiveTextSize(title, 13f)
        title.setTypeface(null, Typeface.BOLD)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        titleLp.setMargins(host.dp(11), 0, 0, 0)
        row.addView(title, titleLp)

        val toggle = smallAction(
            host.getString(if (enabled) R.string.game_scan_disable else R.string.game_scan_enable),
            enabled
        )
        toggle.setOnClickListener {
            val states = getScanRootEnabledStates().toMutableList()
            while (states.size <= index) states.add(true)
            states[index] = !states[index]
            saveScanRootEnabledStates(states)
            renderScanDirectories()
        }
        row.addView(toggle)

        val remove = smallAction(host.getString(R.string.game_common_remove), false)
        remove.setOnClickListener { confirmRemoveDirectory(index) }
        val removeLp = LinearLayout.LayoutParams(host.dp(47), host.dp(29))
        removeLp.setMargins(host.dp(7), 0, 0, 0)
        row.addView(remove, removeLp)

        val rowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            host.dp(52)
        )
        rowLp.setMargins(0, 0, 0, host.dp(9))
        row.layoutParams = rowLp
        return row
    }

    private fun smallAction(text: String, selected: Boolean): TextView {
        val view = TextView(host.requireContext())
        view.text = text
        view.gravity = Gravity.CENTER
        view.isSingleLine = true
        host.setResponsiveTextSize(view, 11f)
        view.setTypeface(null, Typeface.BOLD)
        if (selected) {
            view.setTextColor(LauncherTheme.onPrimary(host.requireContext()))
            view.background = LauncherTheme.selectedChip(host.requireContext())
        } else {
            LauncherTheme.menuItem(view)
        }
        view.layoutParams = LinearLayout.LayoutParams(host.dp(47), host.dp(29))
        return view
    }

    private fun confirmRemoveDirectory(index: Int) {
        host.showConfirmDialog(
            host.getString(R.string.game_scan_remove_title),
            host.getString(R.string.game_scan_remove_message),
            host.getString(R.string.game_common_remove)
        ) {
            val roots = getScanRootUris().toMutableList()
            val states = getScanRootEnabledStates().toMutableList()
            if (index >= 0 && index < roots.size) roots.removeAt(index)
            if (index >= 0 && index < states.size) states.removeAt(index)
            saveScanRootUris(roots)
            saveScanRootEnabledStates(states)
            renderScanDirectories()
        }
    }

    fun saveScanRootUris(roots: List<String?>?) {
        val cleaned = ArrayList<String>()
        if (roots != null) {
            for (root in roots) {
                val value = root?.trim() ?: ""
                if (value.isNotEmpty() && !cleaned.contains(value)) cleaned.add(value)
                if (cleaned.size >= MAX_SCAN_ROOTS) break
            }
        }
        val joined = StringBuilder()
        for (root in cleaned) {
            if (joined.isNotEmpty()) joined.append('\n')
            joined.append(root)
        }
        val editor = host.prefs.edit().putString(ScanRootKeys.KEY_SCAN_ROOT_URIS, joined.toString())
        if (cleaned.isEmpty()) editor.remove(ScanRootKeys.KEY_LAST_SCAN_ROOT_URI)
        else editor.putString(ScanRootKeys.KEY_LAST_SCAN_ROOT_URI, cleaned[0])
        editor.apply()
    }

    fun saveScanRootEnabledStates(states: List<Boolean>?) {
        val joined = StringBuilder()
        val roots = getScanRootUris()
        val count = minOf(MAX_SCAN_ROOTS, roots.size)
        for (i in 0 until count) {
            if (i > 0) joined.append(',')
            val enabled = states == null || i >= states.size || states[i]
            joined.append(if (enabled) '1' else '0')
        }
        host.prefs.edit().putString(ScanRootKeys.KEY_SCAN_ROOT_ENABLED, joined.toString()).apply()
    }

    fun getScanRootUris(): List<String> {
        val roots = ArrayList<String>()
        val joined = host.prefs.getString(ScanRootKeys.KEY_SCAN_ROOT_URIS, "")
        if (joined != null && joined.trim().isNotEmpty()) {
            for (part in joined.split('\n').dropLastWhile { it.isEmpty() }) {
                val root = part.trim()
                if (root.isNotEmpty() && !roots.contains(root)) roots.add(root)
                if (roots.size >= MAX_SCAN_ROOTS) break
            }
        }
        val legacy = host.prefs.getString(ScanRootKeys.KEY_LAST_SCAN_ROOT_URI, "")
        if (roots.isEmpty() && legacy != null && legacy.trim().isNotEmpty()) roots.add(legacy.trim())
        return roots
    }

    fun getActiveScanRootUris(): List<String> {
        val roots = getScanRootUris()
        val states = getScanRootEnabledStates()
        val active = ArrayList<String>()
        for (i in roots.indices) {
            if (i < states.size && states[i]) active.add(roots[i])
        }
        return active
    }

    fun getScanRootEnabledStates(): List<Boolean> {
        val states = ArrayList<Boolean>()
        val joined = host.prefs.getString(ScanRootKeys.KEY_SCAN_ROOT_ENABLED, "")
        if (joined != null && joined.trim().isNotEmpty()) {
            for (part in joined.split(',').dropLastWhile { it.isEmpty() }) {
                states.add(part?.trim() == "1")
            }
        }
        while (states.size < MAX_SCAN_ROOTS) states.add(true)
        return states
    }

    fun scanDepth(): Int {
        val depth = host.prefs.getInt(ScanRootKeys.KEY_STARTUP_SCAN_DEPTH, DEFAULT_SCAN_DEPTH)
        if (depth == LauncherScanBridge.SCAN_ALL_LEVELS || depth == LauncherScanBridge.SCAN_UNTIL_GAME_MATCH) {
            return depth
        }
        return depth.coerceIn(1, MAX_SCAN_DEPTH)
    }

    private fun directoryLabel(root: String): String {
        if (root.trim().isEmpty()) {
            return host.getString(R.string.game_directory_unnamed)
        }
        val last = Uri.parse(root).lastPathSegment
        if (last == null || last.trim().isEmpty()) return root
        val colon = last.lastIndexOf(':')
        return if (colon >= 0 && colon < last.length - 1) last.substring(colon + 1) else last
    }
}
