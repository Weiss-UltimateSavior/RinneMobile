package com.apps.PadUi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.apps.game.GameMetadataFormatter
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherCoverLoader
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.core.R
import com.core.databinding.ItemPadGameRecentBinding
import com.core.model.Game
import com.core.util.TimeFormatUtil

/**
 * Pad GAME 的竖向动态列表：使用从竖屏首页复制出的专用列表卡样式。
 * 聚焦放大由 RecyclerView 宿主负责，这里只负责游戏数据绑定与差分更新。
 */
class PadGameListAdapter : RecyclerView.Adapter<PadGameListAdapter.Holder>() {
    interface OnGameCardListener {
        fun onGameClick(game: Game?)
        fun onGameLongClick(game: Game?)
    }

    private val games = ArrayList<Game>()
    private var listener: OnGameCardListener? = null

    init {
        setHasStableIds(true)
    }

    fun setOnGameCardListener(listener: OnGameCardListener?) {
        this.listener = listener
    }

    fun submit(newGames: List<Game>?, forceFullRefresh: Boolean = false) {
        val next = ArrayList(newGames.orEmpty())
        val old = ArrayList(games)
        games.clear()
        games.addAll(next)
        if (forceFullRefresh) {
            notifyDataSetChanged()
            return
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = games.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                old[oldPosition].id == games[newPosition].id

            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                val before = old[oldPosition]
                val after = games[newPosition]
                return before.id == after.id && before.title == after.title &&
                    before.totalPlayTime == after.totalPlayTime &&
                    before.playStatus == after.playStatus && before.favorite == after.favorite &&
                    before.coverPersistUri == after.coverPersistUri && before.coverUri == after.coverUri
            }
        }).dispatchUpdatesTo(this)
    }

    /** Rebinds visible and cached cards after the runtime Launcher theme tone changes. */
    fun refreshThemeTone() {
        if (games.isNotEmpty()) notifyItemRangeChanged(0, games.size)
    }

    override fun getItemId(position: Int): Long = games[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPadGameRecentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(games[position])
    }

    override fun getItemCount(): Int = games.size

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
    }

    inner class Holder(private val binding: ItemPadGameRecentBinding) : RecyclerView.ViewHolder(binding.root) {
        private var game: Game? = null

        init {
            val cornerRadius = LauncherTheme.dp(binding.root.context, 8f).toFloat()
            binding.recentIcon.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, cornerRadius)
                .setBottomLeftCorner(CornerFamily.ROUNDED, cornerRadius)
                .setTopRightCorner(CornerFamily.ROUNDED, 0f)
                .setBottomRightCorner(CornerFamily.ROUNDED, 0f)
                .build()
            binding.root.setOnClickListener { listener?.onGameClick(game) }
            binding.root.setOnLongClickListener {
                listener?.onGameLongClick(game)
                true
            }
        }

        fun bind(value: Game) {
            game = value
            val title = GameMetadataFormatter.safeTitle(value)
            binding.recentTitle.text = title
            binding.recentTitle.setTextColor(
                if (value.favorite) LauncherTheme.primary(binding.root.context)
                else LauncherTheme.text(binding.root.context)
            )
            binding.recentMeta.text = GameMetadataFormatter.playStatusText(
                binding.root.context,
                value.playStatus,
            )
            binding.recentStatus.text = if (value.totalPlayTime > 0L) {
                TimeFormatUtil.playTime(value.totalPlayTime)
            } else {
                binding.root.context.getString(R.string.game_status_never_played)
            }
            binding.recentStatus.setTextColor(LauncherTheme.primary(binding.root.context))
            binding.root.scaleX = 1f
            binding.root.scaleY = 1f
            binding.root.alpha = 1f
            bindCover(value)
        }

        fun recycle() {
            LauncherCoverLoader.clear(binding.recentIcon)
        }

        private fun bindCover(value: Game) {
            val persistedCover = value.coverPersistUri?.trim().orEmpty()
            val cover = if (persistedCover.isNotEmpty()) persistedCover else value.coverUri?.trim().orEmpty()
            binding.recentIcon.background = LauncherTheme.primaryGradientCard(binding.root.context, 8f)
            LauncherCoverLoader.clear(binding.recentIcon)
            if (cover.isNotEmpty()) {
                LauncherCoverLoader.loadInto(binding.recentIcon, cover, null)
            }
        }
    }
}
