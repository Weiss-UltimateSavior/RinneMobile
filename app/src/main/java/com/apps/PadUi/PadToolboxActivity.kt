package com.apps.PadUi

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.settings.LauncherToolboxFragment
import com.core.R
import com.core.databinding.ActivityPadToolboxBinding

/**
 * Pad 横屏工具箱薄宿主：复用竖屏 [LauncherToolboxFragment]（8 工具点击/确认弹窗/外部打开），
 * 仅承载横屏独立启动路径，布局 activity_pad_toolbox.xml 由 Fragment 按宿主自动选择。
 *
 * 关闭由 [LauncherToolboxFragment] 的 requestClose() 按宿主分派到本类的 finish()。
 */
class PadToolboxActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        PadLandscapeWindow.configure(this)
        setContentView(ActivityPadToolboxBinding.inflate(layoutInflater).root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.padToolboxHostContainer, LauncherToolboxFragment())
                .commit()
        }
    }
}
