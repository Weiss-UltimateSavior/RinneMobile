package com.apps.HDModel

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.game.LauncherSaveGameListFragment
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ItemLauncherManageBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import java.util.LinkedHashMap

/**
 * HD 存档管理页：左侧按内置引擎分类，右侧以子 Fragment 承载存档游戏列表。
 *
 * 重构计划 9.9 阶段 107：嵌入 Activity 迁子 Fragment（[LauncherSaveGameListFragment]），
 * 不再使用 LocalActivityManager；ActivityResult 由子 Fragment 自身注册，可靠性更高。
 */
class HdSaveManagerFragment : Fragment(), HdEmbeddedActivityOwner {
    private var categoryList: LinearLayout? = null
    private var statusView: TextView? = null
    private var detailContainer: FrameLayout? = null
    private var selectedEngine: EngineType? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_hd_save_manager, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        categoryList = view.findViewById(R.id.hdSaveCategoryList)
        statusView = view.findViewById(R.id.hdSaveCategoryStatus)
        detailContainer = view.findViewById(R.id.hdSaveDetailContainer)
        LauncherTheme.applyPrimaryTone(view)
        loadCategories()
    }

    override fun onDestroyView() {
        categoryList = null
        statusView = null
        detailContainer = null
        super.onDestroyView()
    }

    private fun loadCategories() {
        val appContext = requireContext().applicationContext
        AppExecutors.runOnSingle {
            val counts = LinkedHashMap<EngineType, Int>()
            LauncherRepositoryBridge.getAllGames(appContext).forEach { game ->
                if (!LauncherSaveCategoryActivity.isSupportedBuiltInGame(game)) return@forEach
                val engine = game?.engine ?: EngineType.UNKNOWN
                counts[engine] = (counts[engine] ?: 0) + 1
            }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                renderCategories(counts)
            }
        }
    }

    private fun renderCategories(counts: LinkedHashMap<EngineType, Int>) {
        val list = categoryList ?: return
        list.removeAllViews()
        if (counts.isEmpty()) {
            statusView?.setText(R.string.game_save_category_empty)
            return
        }
        statusView?.text = getString(R.string.game_save_category_count, counts.values.sum())
        counts.forEach { (engine, count) -> addCategory(engine, count) }
        showEngine(selectedEngine?.takeIf(counts::containsKey) ?: counts.keys.first())
    }

    private fun addCategory(engine: EngineType, count: Int) {
        val list = categoryList ?: return
        val item = ItemLauncherManageBinding.inflate(layoutInflater, list, false)
        item.manageItemIcon.text = engineIcon(engine)
        item.manageItemTitle.text = getString(
            R.string.game_save_category_row,
            LauncherSaveCategoryActivity.engineLabel(requireContext(), engine),
            count,
        )
        item.root.isClickable = true
        item.root.isFocusable = true
        item.root.setOnClickListener { showEngine(engine) }
        LauncherTheme.applyPrimaryTone(item.root)
        LauncherTheme.styleManageRow(item.root)
        list.addView(item.root)
    }

    private fun showEngine(engine: EngineType) {
        val container = detailContainer ?: return
        selectedEngine = engine
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.launcher_fragment_enter,
                R.anim.launcher_fragment_exit,
            )
            .replace(
                R.id.hdSaveDetailContainer,
                LauncherSaveGameListFragment.newInstance(engine.name),
                SAVE_LIST_TAG,
            )
            .commit()
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val fragment = childFragmentManager.findFragmentByTag(SAVE_LIST_TAG) ?: return false
        childFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.launcher_fragment_enter, R.anim.launcher_fragment_exit)
            .remove(fragment)
            .commit()
        return true
    }

    private fun engineIcon(engine: EngineType): String = when (engine) {
        EngineType.KIRIKIRI -> "K"
        EngineType.ARTEMIS -> "A"
        EngineType.ONS -> "O"
        EngineType.TYRANO -> "T"
        else -> "G"
    }

    companion object {
        private const val SAVE_LIST_TAG = "hd_save_game_list"
    }
}
