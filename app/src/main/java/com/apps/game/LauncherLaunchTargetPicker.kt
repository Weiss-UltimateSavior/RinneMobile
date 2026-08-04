package com.apps.game

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.apps.theme.LauncherDialogFactory
import com.core.R
import com.core.model.EngineType
import com.core.util.AppExecutors
import java.util.Locale

/** Shared startup-target picker for add-game and edit-game forms. */
internal object LauncherLaunchTargetPicker {
    private const val TAG = "LauncherLaunchTargetPicker"
    private const val DIRECTORY_TARGET = "[游戏目录]"

    fun interface Callback {
        fun onTargetSelected(target: String)
    }

    @JvmStatic
    fun show(activity: AppCompatActivity, directoryUri: Uri?, engine: EngineType, callback: Callback?) {
        if (directoryUri == null) {
            Toast.makeText(activity, R.string.game_directory_required, Toast.LENGTH_SHORT).show()
            return
        }
        // 弹窗外壳（透明 window / card 背景 / 动效 / 宽度兜底）统一走 LauncherDialogFactory。
        // 第一阶段：loading 外壳（标题 + “正在扫描”提示），不可取消，生命周期由本方法管理。
        val loading = LauncherDialogFactory.showLoading(
            activity,
            activity.getString(R.string.game_launch_choose_file),
            activity.getString(R.string.game_launch_scanning))
        val appContext = activity.applicationContext
        AppExecutors.runOnIo {
            val targets = scanTargets(appContext, directoryUri, engine)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (!loading.isShowing) return@runOnUiThread
                loading.dismiss()
                if (targets.isEmpty()) {
                    // 无可用目标：沿用“未找到游戏文件”提示语义。
                    LauncherDialogFactory.showInfo(
                        activity,
                        activity.getString(R.string.game_launch_choose_file),
                        activity.getString(R.string.game_launch_no_file))
                    return@runOnUiThread
                }
                val labels = Array<CharSequence>(targets.size) { targets[it].label }
                // 第二阶段：工厂单选列表外壳（checkedIndex 传 -1 表示全部未选中），
                // 选中索引回映射为目标值后走原回调，语义与原实现一致。
                LauncherDialogFactory.showSingleChoice(
                    activity,
                    activity.getString(R.string.game_launch_choose_file),
                    labels,
                    -1
                ) { index ->
                    callback?.onTargetSelected(targets[index].value)
                }
            }
        }
    }

    private fun scanTargets(context: Context, directoryUri: Uri, engine: EngineType): List<Target> {
        val targets = ArrayList<Target>()
        var hasRenpyEntry = engine == EngineType.RENPY
        fun collectTargets(directory: DocumentFile?, prefix: String, level: Int, maxLevel: Int) {
            if (directory == null || !directory.isDirectory()) return
            val files = try {
                directory.listFiles()
            } catch (error: Exception) {
                Log.w(TAG, "list launch target files failed", error)
                return
            }
            for (file in files) {
                if (file == null) continue
                val name = safeName(file)
                val lower = name.lowercase(Locale.ROOT)
                if (lower.isEmpty()) continue
                val isDirectory = try {
                    file.isDirectory()
                } catch (e: Exception) {
                    Log.d(TAG, "isDirectory check failed: $file", e)
                    false
                }
                val target = if (prefix.isEmpty()) name else "$prefix/$name"
                if (isDirectory) {
                    if (level < maxLevel) collectTargets(file, target, level + 1, maxLevel)
                    continue
                }
                if (isRenpyFile(lower)) hasRenpyEntry = true
                if (isGameFile(lower)) targets.add(Target(target, target))
            }
        }
        try {
            val root = DocumentFile.fromTreeUri(context, directoryUri)
            collectTargets(root, "", 1, 2)
        } catch (e: Exception) {
            Log.w(TAG, "scanLaunchTargets failed", e)
        }
        if (hasRenpyEntry) {
            targets.add(0, Target(context.getString(R.string.game_launch_renpy_directory), DIRECTORY_TARGET))
        }
        return targets
    }

    private fun isGameFile(lowerName: String): Boolean {
        if (lowerName.endsWith(".xp3") || lowerName.endsWith(".pfs")
            || lowerName.endsWith(".iso") || lowerName.endsWith(".cso")
            || lowerName.endsWith(".chd") || lowerName.endsWith(".elf")
            || lowerName.endsWith(".pbp") || lowerName.endsWith(".xci")
            || lowerName.endsWith(".nsp") || lowerName.endsWith(".nca")
            || lowerName.endsWith(".nro") || lowerName.endsWith(".desktop")
            || lowerName.endsWith(".exe") || isRenpyFile(lowerName)) return true
        return lowerName == "0.txt" || lowerName == "00.txt"
            || lowerName == "nscript.dat" || lowerName == "nscr_sec.dat"
            || lowerName == "onscript.nt2" || lowerName == "onscript.nt3"
            || lowerName == "index.html" || lowerName == "startup.tjs"
    }

    private fun isRenpyFile(lowerName: String): Boolean {
        return lowerName.endsWith(".rpa") || lowerName.endsWith(".rpy") || lowerName.endsWith(".rpyc")
    }

    private fun safeName(file: DocumentFile): String {
        return try {
            file.name ?: ""
        } catch (error: Exception) {
            Log.d(TAG, "read launch target name failed", error)
            ""
        }
    }

    private class Target(val label: String, val value: String)
}
