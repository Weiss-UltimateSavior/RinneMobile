package com.core.nativeplugin

import android.content.Context
import android.content.res.Resources
import android.content.pm.PackageManager
import android.os.Build
import com.core.engine.EnginePrefs
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

enum class NativePluginInstallState {
    NOT_INSTALLED,
    INSTALLED_ENABLED,
    INSTALLED_DISABLED,
    INVALID,
}

data class NativePluginReadyResult(
    val ready: Boolean,
    val state: NativePluginInstallState,
    val code: String,
)

/**
 * Manages installed native engine plugin state from app-private storage.
 *
 * All methods are safe to call from Java. File operations should run on an IO thread when
 * called from UI because validity checks touch app-private plugin files.
 */
object NativePluginManager {
    private const val ROOT_DIR = "engine_plugins"
    private const val CURRENT_DIR = "current"
    private const val MANIFEST_JSON = "manifest.json"

    @JvmStatic
    fun kirikiroid2RootDir(context: Context): File =
        File(File(context.applicationContext.filesDir, ROOT_DIR), NativePluginConstants.ENGINE_KIRIKIROID2)

    @JvmStatic
    fun kirikiroid2CurrentDir(context: Context): File = File(kirikiroid2RootDir(context), CURRENT_DIR)

    @JvmStatic
    fun isKirikiroid2Installed(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED, false) &&
            validateKirikiroid2Directory(kirikiroid2CurrentDir(context))

    @JvmStatic
    fun isKirikiroid2Enabled(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED, false)

    @JvmStatic
    fun setKirikiroid2Enabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun kirikiroid2InstallState(context: Context): NativePluginInstallState {
        val current = kirikiroid2CurrentDir(context)
        if (!current.exists()) return NativePluginInstallState.NOT_INSTALLED
        if (!validateKirikiroid2Directory(current)) return NativePluginInstallState.INVALID
        return if (isKirikiroid2Enabled(context)) {
            NativePluginInstallState.INSTALLED_ENABLED
        } else {
            NativePluginInstallState.INSTALLED_DISABLED
        }
    }

    @JvmStatic
    fun requireKirikiroid2Ready(context: Context): NativePluginReadyResult {
        val state = kirikiroid2InstallState(context)
        return when (state) {
            NativePluginInstallState.INSTALLED_ENABLED ->
                NativePluginReadyResult(true, state, "ready")
            NativePluginInstallState.INSTALLED_DISABLED ->
                NativePluginReadyResult(false, state, "disabled")
            NativePluginInstallState.INVALID ->
                NativePluginReadyResult(false, state, "invalid")
            NativePluginInstallState.NOT_INSTALLED ->
                NativePluginReadyResult(false, state, "not_installed")
        }
    }

    @JvmStatic
    fun deleteKirikiroid2(context: Context): Boolean {
        val root = kirikiroid2RootDir(context)
        prepareDirectoryForDelete(root)
        val deleted = !root.exists() || root.deleteRecursively()
        if (deleted) clearKirikiroid2Metadata(context)
        return deleted
    }

    @JvmStatic
    fun kirikiroid2LibPath(context: Context, libName: String?): String? {
        val safeName = libName?.trim() ?: return null
        if (!NativePluginConstants.KIRIKIROID2_REQUIRED_LIBS.contains(safeName)) return null
        val file = File(File(kirikiroid2CurrentDir(context), NativePluginConstants.ABI_ARM64), safeName)
        return if (file.isFile) file.absolutePath else null
    }

    @JvmStatic
    fun expectedKirikiroid2ZipSha256(context: Context): String? {
        val override = overridePrefs(context).getString(
            EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_EXPECTED_ZIP_SHA256,
            null,
        )
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (override != null) return override
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
            expectedSha256FromMetaData(context, appInfo.metaData)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun expectedSha256FromMetaData(context: Context, metaData: android.os.Bundle?): String? {
        if (metaData == null) return null
        val key = NativePluginConstants.META_KIRIKIROID2_EXPECTED_ZIP_SHA256
        val directValue = metaData.getString(key)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (directValue != null) return directValue
        val resId = metaData.getInt(key, 0)
        if (resId == 0) return null
        return try {
            context.getString(resId)
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    internal fun validateKirikiroid2Directory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val abiDir = File(directory, NativePluginConstants.ABI_ARM64)
        if (!abiDir.isDirectory) return false
        for (lib in NativePluginConstants.KIRIKIROID2_REQUIRED_LIBS) {
            if (!File(abiDir, lib).isFile) return false
        }
        return validateManifest(File(directory, MANIFEST_JSON))
    }

    internal fun recordKirikiroid2Install(
        context: Context,
        zipSha256: String,
        pluginVersion: Int,
        bridgeAbi: Int,
        installedAt: Long,
    ) {
        prefs(context).edit()
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED, true)
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED, true)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_VERSION, pluginVersion)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ABI, NativePluginConstants.ABI_ARM64)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ZIP_SHA256, zipSha256)
            .putLong(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED_AT, installedAt)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_BRIDGE_ABI, bridgeAbi)
            .apply()
    }

    internal fun clearKirikiroid2Metadata(context: Context) {
        prefs(context).edit()
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_VERSION)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ABI)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ZIP_SHA256)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED_AT)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_BRIDGE_ABI)
            .apply()
    }

    internal fun prepareDirectoryForDelete(directory: File) {
        if (!directory.exists()) return
        directory.walkTopDown().forEach { file ->
            file.setWritable(true, true)
        }
    }

    internal fun parseManifest(directory: File): JSONObject? =
        try {
            JSONObject(File(directory, MANIFEST_JSON).readText(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }

    private fun validateManifest(file: File): Boolean {
        val manifest = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            return false
        } catch (_: JSONException) {
            return false
        }
        if (manifest.optString("engineId") != NativePluginConstants.ENGINE_KIRIKIROID2) return false
        if (manifest.optString("abi") != NativePluginConstants.ABI_ARM64) return false
        if (manifest.optInt("bridgeAbi", -1) != NativePluginConstants.KIRIKIROID2_BRIDGE_ABI) return false
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NativePluginConstants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun overridePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(EnginePrefs.NATIVE_PLUGIN_OVERRIDE_PREFS, Context.MODE_PRIVATE)
}
