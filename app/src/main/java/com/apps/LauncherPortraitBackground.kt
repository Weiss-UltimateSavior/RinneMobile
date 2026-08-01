package com.apps

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.ImageView
import com.core.util.SafeImageLoader
import java.io.File

/** Manages the optional image drawn behind every portrait Launcher Fragment. */
object LauncherPortraitBackground {
    private const val CUSTOM_BACKGROUND_IMAGE_FILE = "launcher_portrait_background_image"

    /**
     * Keep a private copy instead of retaining the picker URI: grants returned by GetContent are
     * temporary and would otherwise make the background disappear after a process restart.
     */
    @JvmStatic
    fun customImageFile(context: Context): File =
        File(context.applicationContext.filesDir, CUSTOM_BACKGROUND_IMAGE_FILE)

    @JvmStatic
    fun hasCustomImage(context: Context): Boolean = customImageFile(context).isFile

    /** Loads a screen-sized sampled bitmap, leaving the theme color visible as the fallback. */
    @JvmStatic
    fun apply(context: Context, imageView: ImageView?) {
        if (imageView == null) return
        val imageFile = customImageFile(context)
        if (!imageFile.isFile) {
            // Advance SafeImageLoader's request id so an older asynchronous decode cannot restore
            // a background after the user has switched back to the solid color.
            SafeImageLoader.loadUri(imageView, null, null)
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            return
        }

        imageView.visibility = View.INVISIBLE
        val uriText = Uri.fromFile(imageFile).toString()
        imageView.post {
            if (!imageFile.isFile) {
                SafeImageLoader.loadUri(imageView, null, null)
                imageView.setImageDrawable(null)
                imageView.visibility = View.GONE
                return@post
            }
            val started = SafeImageLoader.loadUri(
                imageView,
                uriText,
                SafeImageLoader.Callback { success ->
                    imageView.visibility = if (success) View.VISIBLE else View.GONE
                },
            )
            if (!started) imageView.visibility = View.GONE
        }
    }

    /** Call after replacing or deleting the private file so the active Launcher cannot show stale data. */
    @JvmStatic
    fun invalidate(context: Context) {
        SafeImageLoader.invalidateUri(Uri.fromFile(customImageFile(context)).toString())
    }
}
