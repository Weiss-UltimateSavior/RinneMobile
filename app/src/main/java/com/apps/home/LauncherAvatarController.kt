package com.apps.home

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apps.LauncherPreferences
import com.apps.HDModel.LauncherDialogRouter
import com.apps.util.LauncherAvatarPersistence
import com.apps.widget.AvatarCropActivity
import com.core.R
import com.core.databinding.FragmentLauncherHomeBinding
import com.core.launcherbridge.LauncherAuthBridge
import com.core.util.AppExecutors
import com.core.util.RxMainScheduler
import com.core.util.SafeImageLoader

/**
 * 主页头像控制器：持有头像选择/裁剪 ActivityResultLauncher 与头像渲染/持久化逻辑。
 *
 * 生命周期严格被 [LauncherHomeFragment] 包裹：launcher 在字段初始化时经
 * fragment.registerForActivityResult 注册；binding 经 [bindingProvider] 获取，
 * View 销毁后返回 null 作为守卫（§8 持有 Fragment 的协调类模式）。
 */
internal class LauncherAvatarController(
    private val fragment: Fragment,
    private val bindingProvider: () -> FragmentLauncherHomeBinding?
) {

    private val avatarPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            startCrop(uri)
        }

    private val cropLauncher: ActivityResultLauncher<Intent> =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val outputUri = result.data?.getStringExtra(AvatarCropActivity.EXTRA_OUTPUT_URI)
                if (!outputUri.isNullOrEmpty()) {
                    copyAvatarToInternal(Uri.parse(outputUri))
                }
            }
        }

    private fun startCrop(sourceUri: Uri) {
        val intent = Intent(fragment.requireContext(), AvatarCropActivity::class.java)
        intent.putExtra(AvatarCropActivity.EXTRA_INPUT_URI, sourceUri.toString())
        cropLauncher.launch(intent)
    }

    fun showChangeAvatarDialog() {
        LauncherDialogRouter.showStandardConfirm(
            fragment.requireContext(),
            fragment.getString(R.string.home_change_avatar),
            fragment.getString(R.string.home_change_avatar_message),
            fragment.getString(R.string.core_confirm)
        ) {
            avatarPickerLauncher.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }
    }

    private fun copyAvatarToInternal(sourceUri: Uri) {
        val app = fragment.requireContext().applicationContext
        // 文件与偏好持久化已下沉 LauncherAvatarPersistence（§5.2 项 1）；
        // 由应用级任务承载，不随 Home View 销毁而取消。
        AppExecutors.runOnSingle {
            val savedUri = LauncherAvatarPersistence.copyAvatarToInternal(app, sourceUri)
            val success = savedUri != null
            RxMainScheduler.post {
                if (!fragment.isAdded || bindingProvider() == null) return@post
                if (!success) {
                    Toast.makeText(app, R.string.home_avatar_save_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                renderAvatar()
                Toast.makeText(app, R.string.home_avatar_updated, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun renderAvatar() {
        val currentBinding = bindingProvider() ?: return
        // 优先使用主页头像，再检查个人页头像
        var avatar = prefs().getString(LauncherAvatarPersistence.KEY_PROFILE_AVATAR, "")
        if (avatar == null || avatar.trim { it <= ' ' }.isEmpty()) {
            val profileAvatar = fragment.requireContext()
                .getSharedPreferences(LauncherPreferences.PROFILE_PREFS, 0)
                .getString(LauncherAvatarPersistence.KEY_CUSTOM_AVATAR, "")
            if (profileAvatar != null && profileAvatar.trim { it <= ' ' }.isNotEmpty()) {
                avatar = profileAvatar
            }
        }
        // 更新首字母
        val nickname = if (LauncherAuthBridge.isLoggedIn(fragment.requireContext())) {
            LauncherAuthBridge.getNickname(fragment.requireContext())
        } else {
            ""
        }
        val initial = if (nickname.trim { it <= ' ' }.isNotEmpty()) {
            nickname.trim { it <= ' ' }.substring(0, 1).uppercase()
        } else {
            fragment.getString(R.string.launcher_avatar_fallback_initial)
        }
        currentBinding.launcherAvatarInitial.text = initial

        if (avatar == null || avatar.trim { it <= ' ' }.isEmpty()) {
            currentBinding.launcherAvatarImage.setImageDrawable(null)
            currentBinding.launcherAvatarImage.visibility = View.GONE
            currentBinding.launcherAvatarInitial.visibility = View.VISIBLE
            return
        }
        try {
            currentBinding.launcherAvatarImage.clipToOutline = true
            // 先显示回退态；缓存命中时 SafeImageLoader 会同步回填并立即覆盖此状态。
            currentBinding.launcherAvatarImage.visibility = View.GONE
            currentBinding.launcherAvatarInitial.visibility = View.VISIBLE
            if (!SafeImageLoader.loadUri(
                    currentBinding.launcherAvatarImage,
                    avatar,
                    SafeImageLoader.Callback { success ->
                        val cb = bindingProvider() ?: return@Callback
                        if (success) {
                            cb.launcherAvatarImage.visibility = View.VISIBLE
                            cb.launcherAvatarInitial.visibility = View.GONE
                        } else {
                            showDefaultAvatar()
                        }
                    }
                )
            ) {
                showDefaultAvatar()
                return
            }
        } catch (error: RuntimeException) {
            // 头像加载兜底：SafeImageLoader 已内部返回 false，此处仅防运行时异常
            showDefaultAvatar()
        }
    }

    private fun showDefaultAvatar() {
        val currentBinding = bindingProvider() ?: return
        val nickname = if (LauncherAuthBridge.isLoggedIn(fragment.requireContext())) {
            LauncherAuthBridge.getNickname(fragment.requireContext())
        } else {
            ""
        }
        val initial = if (nickname.trim { it <= ' ' }.isNotEmpty()) {
            nickname.trim { it <= ' ' }.substring(0, 1).uppercase()
        } else {
            fragment.getString(R.string.launcher_avatar_fallback_initial)
        }
        currentBinding.launcherAvatarInitial.text = initial
        currentBinding.launcherAvatarImage.setImageDrawable(null)
        currentBinding.launcherAvatarImage.visibility = View.GONE
        currentBinding.launcherAvatarInitial.visibility = View.VISIBLE
    }

    private fun prefs(): SharedPreferences =
        fragment.requireContext().applicationContext.getSharedPreferences(
            LauncherPreferences.APP_PREFS, android.content.Context.MODE_PRIVATE
        )
}
