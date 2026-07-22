package com.yuki.yukihub.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/** PSP 与 Nintendo 3DS 外部模拟器的 Intent 构建和可用性探测。 */
internal object HandheldLaunchers {
    private const val TAG = "EmulatorLauncher"
    private const val PPSSPP_PACKAGE = "org.ppsspp.ppsspp"
    private const val PPSSPP_ACTIVITY = "org.ppsspp.ppsspp.PpssppActivity"
    private const val AZAHAR_PACKAGE = "io.github.azaharplus.android"
    private const val CITRA_ACTIVITY = "org.citra.citra_emu.activities.EmulationActivity"
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
