package com.apps.game

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.apps.theme.LauncherTheme
import com.apps.widget.LauncherCoverLoader
import com.core.R
import com.core.databinding.ItemLauncherGameCardBinding
import com.core.model.Game
import com.core.util.TimeFormatUtil
import kotlin.math.max

/** Shared data, selection, diffing and binding contract for portrait and Pad game cards. */
abstract class BaseGameCardAdapter protected constructor(
    private val layoutSpec: CardLayoutSpec?,
    private val updateAttachedHeightsOnly: Boolean,
) : RecyclerView.Adapter<BaseGameCardAdapter.Holder>() {
    init {
        setHasStableIds(true)
    }

    /** Keeps layout-only variations out of the shared card data/binding pipeline. */
    fun interface CardLayoutSpec {
        fun apply(binding: ItemLauncherGameCardBinding?, fixedHeightPx: Int)
    }

    /** 游戏卡片点击/长按回调。 */
    interface OnGameCardListener {
        fun onGameClick(game: Game?)
        fun onGameLongClick(game: Game?)
    }

    private val games = ArrayList<Game>()
    private var listener: OnGameCardListener? = null
    private var selectedGameId = -1L
    private var fixedCardHeight = 0
    private var attachedRecyclerView: RecyclerView? = null
    private var posterStyle = false

    fun setOnGameCardListener(listener: OnGameCardListener?) {
        this.listener = listener
    }

    /** Uses a portrait-cover grid with text below the cover, rather than the compact overlay card. */
    fun setPosterStyle(enabled: Boolean) {
        if (posterStyle == enabled) return
        posterStyle = enabled
        fixedCardHeight = 0
        notifyDataSetChanged()
    }

    fun isPosterStyle(): Boolean = posterStyle

    fun setFixedCardHeight(heightPx: Int) {
        val next = max(0, heightPx)
        if (next == fixedCardHeight) return
        fixedCardHeight = next
        if (updateAttachedHeightsOnly) applyHeightToAttached() else notifyDataSetChanged()
    }

    fun submit(newGames: List<Game>?) {
        submit(newGames, false)
    }

    fun submit(newGames: List<Game>?, forceFullRefresh: Boolean) {
        val next = if (newGames == null) ArrayList() else ArrayList(newGames)
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
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                sameContent(old[oldPosition], games[newPosition])
        }).dispatchUpdatesTo(this)
    }

    fun setSelectedGameId(id: Long) {
        val old = selectedGameId
        selectedGameId = id
        for (i in games.indices) {
            val game = games[i]
            if (game.id == old || game.id == id) notifyItemChanged(i)
        }
    }

    override fun getItemId(position: Int): Long = games[position].id

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLauncherGameCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        applyLayout(binding)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val game = games[position]
        holder.bind(game, game.id == selectedGameId)
    }

    override fun getItemCount(): Int = games.size

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
    }

    inner class Holder(binding: ItemLauncherGameCardBinding) : RecyclerView.ViewHolder(binding.root) {
        private val binding = binding
        private var currentGame: Game? = null

        private val coverCallback = object : LauncherCoverLoader.Callback {
            override fun onLoaded(success: Boolean) {
                if (success) {
                    binding.launcherGameCover.visibility = View.VISIBLE
                    binding.launcherGameInitial.visibility = View.GONE
                }
            }
        }

        init {
            binding.root.setOnClickListener {
                val game = currentGame ?: return@setOnClickListener
                setSelectedGameId(game.id)
                listener?.onGameClick(game)
            }
            binding.root.setOnLongClickListener {
                val game = currentGame ?: return@setOnLongClickListener true
                setSelectedGameId(game.id)
                listener?.onGameLongClick(game)
                true
            }
        }

        internal fun bind(game: Game?, selected: Boolean) {
            if (game == null) return
            currentGame = game
            applyLayout(binding)
            val gameTitle = title(binding.root, game)
            val playStatus = if (game.totalPlayTime <= 0L) {
                binding.root.context.getString(R.string.game_status_never_played)
            } else {
                TimeFormatUtil.playTime(game.totalPlayTime)
            }
            binding.root.setBackgroundResource(
                if (posterStyle) android.R.color.transparent
                else if (selected) R.drawable.launcher_game_card_selected
                else R.drawable.launcher_game_card
            )
            binding.launcherGameTitle.text = gameTitle
            binding.launcherGamePlayStatus.text = playStatus
            binding.launcherGamePosterTitle.text = posterTitle(gameTitle)
            binding.launcherGamePosterStatus.text = playStatus
            applyCardPresentation()
            binding.launcherGameInitial.text = initial(binding.root, game.title)
            applyFavoriteAppearance(game.favorite)
            binding.launcherGameInitial.setTextColor(LauncherTheme.text(binding.root.context))
            bindCover(game)
        }

        private fun applyCardPresentation() {
            binding.launcherGameTextOverlay.visibility = if (posterStyle) View.GONE else View.VISIBLE
            binding.launcherGamePosterInfo.visibility = if (posterStyle) View.VISIBLE else View.GONE
            if (!posterStyle) {
                val cover = binding.launcherGameCoverFrame.layoutParams
                if (cover.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    cover.height = ViewGroup.LayoutParams.MATCH_PARENT
                    binding.launcherGameCoverFrame.layoutParams = cover
                }
                val root = binding.root.layoutParams
                val normalHeight = if (fixedCardHeight > 0) fixedCardHeight else LauncherTheme.dp(binding.root.context, 144)
                if (root.height != normalHeight) {
                    root.height = normalHeight
                    binding.root.layoutParams = root
                }
                return
            }
            binding.root.post {
                if (!posterStyle) return@post
                val width = binding.root.width
                if (width <= 0) return@post
                val coverHeight = Math.round(width * 1.42f)
                // 标题（15sp）和游玩时间（12sp）实际只需约 35dp；保留 40dp，
                // 避免此前 64dp 的空白被视觉上误认为卡片之间的巨大间距。
                val infoHeight = LauncherTheme.dp(binding.root.context, 40)
                val cover = binding.launcherGameCoverFrame.layoutParams
                if (cover.height != coverHeight) {
                    cover.height = coverHeight
                    binding.launcherGameCoverFrame.layoutParams = cover
                }
                val root = binding.root.layoutParams
                val rootHeight = coverHeight + infoHeight
                if (root.height != rootHeight) {
                    root.height = rootHeight
                    binding.root.layoutParams = root
                }
            }
        }

        private fun applyFavoriteAppearance(favorite: Boolean) {
            if (favorite) {
                binding.launcherGameTextOverlay.background = LauncherTheme.primaryTextOverlay(binding.root.context)
                val onPrimary = LauncherTheme.onPrimary(binding.root.context)
                binding.launcherGameTitle.setTextColor(onPrimary)
                binding.launcherGamePlayStatus.setTextColor(onPrimary)
            } else {
                binding.launcherGameTextOverlay.setBackgroundResource(R.drawable.launcher_game_text_overlay)
                val text = LauncherTheme.text(binding.root.context)
                binding.launcherGameTitle.setTextColor(text)
                binding.launcherGamePlayStatus.setTextColor(text)
            }
            if (posterStyle) {
                binding.launcherGamePosterTitle.setTextColor(
                    if (favorite) LauncherTheme.primary(binding.root.context) else LauncherTheme.text(binding.root.context)
                )
                binding.launcherGamePosterStatus.setTextColor(
                    if (favorite) LauncherTheme.primary(binding.root.context) else LauncherTheme.textMuted(binding.root.context)
                )
            }
        }

        internal fun recycle() {
            LauncherCoverLoader.clear(binding.launcherGameCover)
        }

        private fun bindCover(game: Game) {
            val persist = game.coverPersistUri
            val uri = game.coverUri
            val cover = if (persist != null && persist.trim().isNotEmpty()) persist.trim() else uri?.trim() ?: ""
            binding.launcherGameCoverFrame.clipToOutline = true
            binding.launcherGameCover.clipToOutline = true
            LauncherCoverLoader.clear(binding.launcherGameCover)
            binding.launcherGameCover.setImageDrawable(null)
            binding.launcherGameCover.visibility = View.GONE
            binding.launcherGameInitial.visibility = View.VISIBLE
            if (cover.isNotEmpty()) {
                LauncherCoverLoader.loadInto(binding.launcherGameCover, cover, coverCallback)
            }
        }
    }

    private fun applyHeightToAttached() {
        val recyclerView = attachedRecyclerView ?: return
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            if (child != null) applyLayout(ItemLauncherGameCardBinding.bind(child))
        }
    }

    private fun applyLayout(binding: ItemLauncherGameCardBinding) {
        layoutSpec?.apply(binding, fixedCardHeight)
    }

    companion object {
        @JvmStatic
        internal fun compactText(binding: ItemLauncherGameCardBinding) {
            binding.launcherGameTextOverlay.setPadding(
                LauncherTheme.dp(binding.root.context, 8),
                LauncherTheme.dp(binding.root.context, 2),
                LauncherTheme.dp(binding.root.context, 8),
                LauncherTheme.dp(binding.root.context, 2),
            )
            binding.launcherGameTitle.isSingleLine = true
            binding.launcherGameTitle.maxLines = 1
            binding.launcherGameTitle.ellipsize = TextUtils.TruncateAt.END
            binding.launcherGameTitle.includeFontPadding = false
            binding.launcherGamePlayStatus.isSingleLine = true
            binding.launcherGamePlayStatus.maxLines = 1
            binding.launcherGamePlayStatus.ellipsize = TextUtils.TruncateAt.END
            binding.launcherGamePlayStatus.includeFontPadding = false
            setMargins(binding.launcherGameTitle, 0, 0, 0, LauncherTheme.dp(binding.root.context, 1))
            setMargins(binding.launcherGamePlayStatus, 0, 0, 0, 0)
        }
    }
}

private fun sameContent(a: Game, b: Game): Boolean =
    a.id == b.id && eq(a.title, b.title) && a.totalPlayTime == b.totalPlayTime &&
        eq(a.playStatus, b.playStatus) && a.favorite == b.favorite &&
        eq(a.coverPersistUri, b.coverPersistUri) && eq(a.coverUri, b.coverUri)

private fun eq(a: String?, b: String?): Boolean = if (a == null) b == null else a == b

private fun title(view: View, game: Game): String {
    val t = game.title
    return if (t == null || t.trim().isEmpty()) view.context.getString(R.string.game_unnamed) else t.trim()
}

private fun posterTitle(value: String): String {
    val maxCharacters = 10
    if (value.isEmpty()) return value
    val count = value.codePointCount(0, value.length)
    if (count <= maxCharacters) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCharacters)) + "..."
}

private fun initial(view: View, title: String?): String {
    if (title == null || title.trim().isEmpty()) {
        return view.context.getString(R.string.game_default_initial)
    }
    val value = title.trim()
    return value.substring(0, value.offsetByCodePoints(0, 1))
}

private fun setMargins(view: View, left: Int, top: Int, right: Int, bottom: Int) {
    val params = view.layoutParams
    if (params !is ViewGroup.MarginLayoutParams) return
    if (params.leftMargin == left && params.topMargin == top && params.rightMargin == right && params.bottomMargin == bottom) return
    params.setMargins(left, top, right, bottom)
    view.layoutParams = params
}
