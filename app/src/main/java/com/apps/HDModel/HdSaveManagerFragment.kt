package com.apps.HDModel

import android.app.LocalActivityManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.apps.game.LauncherSaveCategoryActivity
import com.apps.game.LauncherSaveGameListActivity
import com.apps.theme.LauncherTheme
import com.core.R
import com.core.databinding.ItemLauncherManageBinding
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.model.EngineType
import com.core.model.Game
import com.core.util.AppExecutors
import java.util.LinkedHashMap

/**
 * HD 存档管理页：左侧按内置引擎分类，右侧承载原有存档游戏列表 Activity。
 */
@Suppress("DEPRECATION")
class HdSaveManagerFragment : Fragment(), HdEmbeddedActivityOwner {
    private var localActivityManager: LocalActivityManager? = null
    private var categoryList: LinearLayout? = null
    private var statusView: TextView? = null
    private var detailContainer: FrameLayout? = null
    private var selectedEngine: EngineType? = null
    private var embeddedActivityId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_hd_save_manager, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        localActivityManager = LocalActivityManager(requireActivity(), false).apply {
            dispatchCreate(savedInstanceState)
        }
        categoryList = view.findViewById(R.id.hdSaveCategoryList)
        statusView = view.findViewById(R.id.hdSaveCategoryStatus)
        detailContainer = view.findViewById(R.id.hdSaveDetailContainer)
        LauncherTheme.applyPrimaryTone(view)
        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        localActivityManager?.dispatchResume()
    }

    override fun onPause() {
        localActivityManager?.dispatchPause(requireActivity().isFinishing)
        super.onPause()
    }

    override fun onStop() {
        localActivityManager?.dispatchStop()
        super.onStop()
    }

    override fun onDestroyView() {
        localActivityManager?.dispatchDestroy(requireActivity().isFinishing)
        localActivityManager = null
        categoryList = null
        statusView = null
        detailContainer = null
        embeddedActivityId = null
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
        val manager = localActivityManager ?: return
        val container = detailContainer ?: return
        selectedEngine = engine
        val intent = Intent(requireContext(), LauncherSaveGameListActivity::class.java)
            .putExtra(LauncherSaveGameListActivity.EXTRA_ENGINE, engine.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = "hd_save_${engine.name}"
        embeddedActivityId = id
        val window = manager.startActivity(id, intent) ?: return
        val content = window.decorView
        HdPageMotion.showEmbedded(container, content)
    }

    override fun closeEmbeddedActivity(child: Activity?): Boolean {
        val manager = localActivityManager ?: return false
        val id = embeddedActivityId ?: return false
        val current = manager.currentActivity
        if (child != null && current != null && current !== child) return false
        embeddedActivityId = null
        detailContainer?.apply {
            HdPageMotion.closeEmbedded(this) {
                post { manager.destroyActivity(id, true) }
            }
        }
        return true
    }

    private fun engineIcon(engine: EngineType): String = when (engine) {
        EngineType.KIRIKIRI -> "K"
        EngineType.ARTEMIS -> "A"
        EngineType.ONS -> "O"
        EngineType.TYRANO -> "T"
        else -> "G"
    }
}
