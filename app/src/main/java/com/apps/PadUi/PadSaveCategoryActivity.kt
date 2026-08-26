package com.apps.PadUi

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apps.LauncherActivity
import com.apps.common.LauncherInsetsHelper
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ActivityPadSaveCategoryBinding
import com.core.databinding.ItemLauncherManageBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/**
 * Pad 横屏存档页：左侧按内置引擎分类，右侧内嵌 [PadSaveGameListFragment] 显示该引擎下的游戏存档。
 *
 * 复用竖屏 [LauncherSaveCategoryActivity] 的静态判断与文案，布局为横屏 master-detail 联动。
 */
class PadSaveCategoryActivity : AppCompatActivity() {

    private var binding: ActivityPadSaveCategoryBinding? = null
    private var selectedEngine: EngineType? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LauncherActivity.wrapLauncherUiMode(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherActivity.applySavedToneMode(this)
        super.onCreate(savedInstanceState)
        PadLandscapeWindow.configure(this)
        val currentBinding = ActivityPadSaveCategoryBinding.inflate(layoutInflater)
        binding = currentBinding
        setContentView(currentBinding.root)
        LauncherInsetsHelper.applyInsets(currentBinding.root, currentBinding.padSaveCategoryContent)
        LauncherTheme.applyPrimaryTone(currentBinding.root)
        currentBinding.padSaveCategoryBackButton.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }
        loadCategories()
    }

    private fun loadCategories() {
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val counts = LinkedHashMap<EngineType, Int>()
            LauncherRepositoryBridge.getAllGames(app).forEach { game ->
                if (!LauncherSaveCategoryActivity.isSupportedBuiltInGame(game)) return@forEach
                val engine = game?.engine ?: EngineType.UNKNOWN
                counts[engine] = (counts[engine] ?: 0) + 1
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                renderCategories(counts)
            }
        }
    }

    private fun renderCategories(counts: LinkedHashMap<EngineType, Int>) {
        val currentBinding = binding ?: return
        currentBinding.padSaveCategoryList.removeAllViews()
        if (counts.isEmpty()) return
        counts.forEach { (engine, count) -> addCategory(currentBinding, engine, count) }
        val engine = selectedEngine?.takeIf(counts::containsKey) ?: counts.keys.first()
        showEngine(engine)
    }

    private fun addCategory(
        currentBinding: ActivityPadSaveCategoryBinding,
        engine: EngineType,
        count: Int,
    ) {
        val item = ItemLauncherManageBinding.inflate(layoutInflater, currentBinding.padSaveCategoryList, false)
        val row = item.root
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { showEngine(engine) }
        item.manageItemIcon.text = engineIcon(engine)
        item.manageItemTitle.text = getString(
            R.string.game_save_category_row,
            LauncherSaveCategoryActivity.engineLabel(this, engine),
            count,
        )
        LauncherTheme.applyPrimaryTone(row)
        LauncherTheme.styleManageRow(row)
        currentBinding.padSaveCategoryList.addView(row)
    }

    /** 在右侧明细容器内嵌对应引擎的存档列表。 */
    private fun showEngine(engine: EngineType) {
        val container = binding?.padSaveCategoryDetailContainer ?: return
        selectedEngine = engine
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
            )
            .replace(
                R.id.padSaveCategoryDetailContainer,
                PadSaveGameListFragment.newInstance(engine.name),
                SAVE_LIST_TAG,
            )
            .commit()
    }

    private fun engineIcon(engine: EngineType): String = when (engine) {
        EngineType.KIRIKIRI -> "K"
        EngineType.ARTEMIS -> "A"
        EngineType.ONS -> "O"
        EngineType.TYRANO -> "T"
        EngineType.RPG_MV -> "MV"
        EngineType.RPG_MZ -> "MZ"
        else -> "G"
    }

    companion object {
        private const val SAVE_LIST_TAG = "pad_save_game_list"
    }
}
