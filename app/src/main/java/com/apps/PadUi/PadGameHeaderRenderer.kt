package com.apps.PadUi

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import androidx.fragment.app.Fragment
import com.apps.LauncherPreferences
import com.apps.util.LauncherAvatarPersistence
import com.core.R
import com.core.databinding.FragmentPadGameBinding
import com.core.launcherbridge.LauncherAuthBridge
import com.core.util.DevLogger
import com.core.util.SafeImageLoader

class PadGameHeaderRenderer(
    private val fragment: Fragment,
    private val binding: FragmentPadGameBinding
) {
    companion object {
        private const val TAG = "PadGameHeaderRenderer"
        private const val KEY_PROFILE_AVATAR = LauncherAvatarPersistence.KEY_PROFILE_AVATAR
        private const val KEY_PROFILE_NAME = LauncherPreferences.KEY_PROFILE_NAME
        private const val KEY_AUTH_STATUS = "auth_status"
        private const val AUTH_STATUS_ONLINE = "online"
        private const val AUTH_STATUS_SYNCING = "syncing"
        private const val AUTH_STATUS_EXPIRED = "expired"
    }

    private fun appPrefs(): SharedPreferences {
        return fragment.requireContext().applicationContext
            .getSharedPreferences(LauncherPreferences.APP_PREFS, Context.MODE_PRIVATE)
    }

    fun renderAvatar() {
        var avatar = appPrefs().getString(KEY_PROFILE_AVATAR, "")
        if (avatar.isNullOrBlank()) {
            val profileAvatar = fragment.requireContext()
                .getSharedPreferences(LauncherPreferences.PROFILE_PREFS, 0)
                .getString(LauncherAvatarPersistence.KEY_CUSTOM_AVATAR, "")
            if (!profileAvatar.isNullOrBlank()) {
                avatar = profileAvatar
            }
        }
        val nickname = if (LauncherAuthBridge.isLoggedIn(fragment.requireContext()))
            LauncherAuthBridge.getNickname(fragment.requireContext()) else ""
        val initial = if (!nickname.isNullOrBlank())
            nickname.trim().first().uppercase() else fragment.getString(R.string.launcher_avatar_fallback_initial)
        binding.padAvatarInitial.text = initial

        if (avatar.isNullOrBlank()) {
            binding.padAvatarImage.setImageDrawable(null)
            binding.padAvatarImage.visibility = View.GONE
            binding.padAvatarInitial.visibility = View.VISIBLE
            return
        }
        try {
            binding.padAvatarImage.clipToOutline = true
            binding.padAvatarImage.visibility = View.GONE
            binding.padAvatarInitial.visibility = View.VISIBLE
            if (!SafeImageLoader.loadUri(binding.padAvatarImage, avatar) { success ->
                    if (!fragment.isAdded) return@loadUri
                    binding.padAvatarImage.visibility = if (success) View.VISIBLE else View.GONE
                    binding.padAvatarInitial.visibility = if (success) View.GONE else View.VISIBLE
                }) {
                binding.padAvatarImage.setImageDrawable(null)
                binding.padAvatarImage.visibility = View.GONE
                binding.padAvatarInitial.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            DevLogger.w(TAG, "avatar load failed: $avatar", e)
            binding.padAvatarImage.setImageDrawable(null)
            binding.padAvatarImage.visibility = View.GONE
            binding.padAvatarInitial.visibility = View.VISIBLE
        }
    }

    fun renderAccountInfo() {
        binding.padAccountName.text = displayName()
        binding.padAccountMode.text = accountMode()
    }

    private fun displayName(): String {
        if (LauncherAuthBridge.isLoggedIn(fragment.requireContext())) {
            val nickname = LauncherAuthBridge.getNickname(fragment.requireContext())
            if (!nickname.isNullOrBlank()) return nickname.trim()
        }
        val profileName = appPrefs().getString(KEY_PROFILE_NAME, "")
        if (!profileName.isNullOrBlank()) return profileName.trim()
        return LauncherPreferences.DEFAULT_PROFILE_NAME
    }

    private fun accountMode(): String {
        if (!LauncherAuthBridge.isLoggedIn(fragment.requireContext())) return fragment.getString(R.string.home_local_mode)
        val status = appPrefs().getString(KEY_AUTH_STATUS, "")
        return when (status) {
            AUTH_STATUS_ONLINE -> fragment.getString(R.string.pad_online_mode)
            AUTH_STATUS_SYNCING -> fragment.getString(R.string.pad_online_syncing)
            AUTH_STATUS_EXPIRED -> fragment.getString(R.string.pad_online_expired)
            else -> fragment.getString(R.string.pad_online_mode)
        }
    }
}
