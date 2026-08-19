package com.core.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.core.util.DevLogger

/** 掌机外部模拟器的 Intent 构建和可用性探测。 */
internal object HandheldLaunchers {
    private const val TAG = "EmulatorLauncher"
    private val PPSSPP_PACKAGE = EnginePackages.EXTERNAL_PPSSPP
    private const val PPSSPP_ACTIVITY = "org.ppsspp.ppsspp.PpssppActivity"
    private val AZAHAR_PACKAGE = EnginePackages.EXTERNAL_AZAHAR
    private const val CITRA_ACTIVITY = "org.citra.citra_emu.activities.EmulationActivity"
    private val EDEN_PACKAGE = EnginePackages.EXTERNAL_EDEN
    private const val EDEN_ACTIVITY = "org.yuzu.yuzu_emu.activities.EmulationActivity"
    private val ARMSX3_PACKAGE = EnginePackages.EXTERNAL_ARMSX3
    // ARMSX3 的 applicationId 是 com.armsx3，但其主 Activity 类名继承自项目原始 naming
    // 空间 com.armsx2（com.armsx2.MainActivity，manifest 中为导出的 activity-alias，带
    // ACTION_VIEW + content/file scheme filter）。包名与 activity 前缀不同是 ARMSX3 上游的
    // 合法配置（应用改名后保留旧类名），已在 ARMSX3 的 AndroidManifest.xml 中核实。
    private const val ARMSX3_ACTIVITY = "com.armsx2.MainActivity"
    private val citraPackages = arrayOf(
        AZAHAR_PACKAGE,
        "io.github.azaharplus.android.debug",
        "org.citra.citra_emu",
        "org.azahar_emu.azahar",
    )

    @JvmStatic
    fun buildPspIntent(context: Context?, gameUri: String?, launchTarget: String?): Intent {
        require(context != null && !gameUri.isNullOrBlank()) { "PSP game URI is empty" }
        val uri = normalizedGameUri(gameUri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            setClassName(PPSSPP_PACKAGE, PPSSPP_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Log.i(TAG, "Built PSP intent uri=$uri")
        }
    }

    @JvmStatic
    fun launchPsp(context: Context, gameUri: String?, launchTarget: String?): Boolean {
        if (!isPpssppInstalled(context)) {
            Log.w(TAG, "PPSSPP is not installed")
            return false
        }
        return try {
            context.startActivity(buildPspIntent(context, gameUri, launchTarget))
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to launch PSP game", error)
            false
        }
    }

    @JvmStatic
    fun isPpssppInstalled(context: Context): Boolean =
        isPackageInstalled(context.packageManager, PPSSPP_PACKAGE)

    @JvmStatic
    fun buildCitraIntent(context: Context?, gameUri: String?, launchTarget: String?): Intent {
        require(context != null && !gameUri.isNullOrBlank()) { "3DS game URI is empty" }
        val uri = normalizedGameUri(gameUri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            setClassName(AZAHAR_PACKAGE, CITRA_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.i(TAG, "Built Citra intent uri=$uri")
        }
    }

    @JvmStatic
    fun launchCitra(context: Context, gameUri: String?, launchTarget: String?): Boolean {
        if (!isCitraInstalled(context)) {
            Log.w(TAG, "Citra/Azahar is not installed")
            return false
        }
        return try {
            context.startActivity(buildCitraIntent(context, gameUri, launchTarget))
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to launch Nintendo 3DS game", error)
            false
        }
    }

    @JvmStatic
    fun isCitraInstalled(context: Context): Boolean =
        citraPackages.any { isPackageInstalled(context.packageManager, it) }

    /** Eden accepts ACTION_VIEW with a readable content URI and application/octet-stream MIME type. */
    @JvmStatic
    fun buildEdenIntent(context: Context?, gameUri: String?, launchTarget: String?): Intent {
        require(context != null && !gameUri.isNullOrBlank()) { "Nintendo Switch game URI is empty" }
        val uri = normalizedGameUri(gameUri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            setClassName(EDEN_PACKAGE, EDEN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.i(TAG, "Built Eden intent uri=$uri")
        }
    }

    @JvmStatic
    fun isEdenInstalled(context: Context): Boolean =
        isPackageInstalled(context.packageManager, EDEN_PACKAGE)

    /** ARMSX3 (RPCS3 Android port) 接受 ACTION_VIEW + content/file URI，外部前端赠予读取授权。 */
    @JvmStatic
    fun buildArmsx3Intent(context: Context?, gameUri: String?): Intent {
        // 消息需同时覆盖 context == null 与 gameUri == null 两条校验分支
        require(context != null && gameUri != null) { "PS3 game context or URI is empty" }
        // 传 content://（SAF tree/document）并带授权，避免 file:// 在 targetSdk>=24 下触发
        // FileUriExposedException。ARMSX3 对 content:// 文件直接按 fd 打开；如需跨重启读取
        // 目录树，由调用方额外 takePersistableUriPermission。
        return Intent(Intent.ACTION_VIEW).apply {
            setData(android.net.Uri.parse(gameUri))
            setClassName(ARMSX3_PACKAGE, ARMSX3_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DevLogger.w(TAG, "Built ARMSX3 intent uri=$gameUri")
        }
    }

    /** 探测 ARMSX3 (com.armsx3) 是否已安装；未安装时启动策略应提前短路。 */
    @JvmStatic
    fun isArmsx3Installed(context: Context): Boolean =
        isPackageInstalled(context.packageManager, ARMSX3_PACKAGE)

    @JvmStatic
    fun ppssppDownloadIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PPSSPP_PACKAGE"),
    )

    private fun normalizedGameUri(gameUri: String): Uri =
        Uri.parse(if (gameUri.startsWith('/')) "file://$gameUri" else gameUri)

    private fun isPackageInstalled(manager: PackageManager, packageName: String): Boolean = try {
        manager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
