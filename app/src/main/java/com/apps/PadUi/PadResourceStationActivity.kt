package com.apps.PadUi

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apps.LauncherActivity
import com.apps.settings.ResourceStationFragment
import com.core.R
import com.core.databinding.ActivityPadResourceStationBinding

/**
 * Pad 横屏资讯站薄宿主：复用竖屏 [ResourceStationFragment]（WebView/顶栏/导航拦截/硬件返回），
 * 仅承载横屏独立启动路径（布局 activity_pad_resource_station.xml）。
 *
 * 关闭由 [ResourceStationFragment] 的 requestClose() 按宿主分派到本类的 finish()。
 */
class PadResourceStationActivity : AppCompatActivity() {

    private var binding: ActivityPadResourceStationBinding? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        PadLandscapeWindow.configure(this)
        val currentBinding = ActivityPadResourceStationBinding.inflate(layoutInflater)
        binding = currentBinding
        setContentView(currentBinding.root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.padResourceStationHostContainer,
                    ResourceStationFragment.newInstance(
                        url = intent.getStringExtra(ResourceStationFragment.EXTRA_URL),
                        title = intent.getStringExtra(ResourceStationFragment.EXTRA_TITLE),
                        hdEmbedded = false,
                    ),
                )
                .commit()
        }
    }
}
