package com.apps.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.core.util.DevLogger

/** Shared best-effort external URL opener for Launcher UI flows. */
object LauncherUrlOpener {
    private const val TAG = "LauncherUrlOpener"
    private val ALLOWED_SCHEMES = setOf("http", "https")

    @JvmStatic
    fun open(context: Context, url: String?): Boolean {
        val safeUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val uri = Uri.parse(safeUrl)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in ALLOWED_SCHEMES) {
            DevLogger.w(TAG, "Blocked external URL with unsupported scheme: ${scheme ?: "<none>"}")
            return false
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (error: ActivityNotFoundException) {
            DevLogger.w(TAG, "No activity available to open external URL", error)
            false
        }
    }
}
