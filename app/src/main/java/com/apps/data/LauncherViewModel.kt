package com.apps.data

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.core.launcherbridge.LauncherRepositoryBridge
import com.core.util.RxMainScheduler
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 顶层 ViewModel，向 [LauncherActivity] 及其 Fragment 暴露不可变的 [LauncherState] 快照。
 *
 * 状态通过 [MutableLiveData] 持有；所有变更都通过 [LauncherState.copy] 产生新实例后 setValue，
 * 保证观察者拿到的快照之间互不影响。
 */
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    enum class NavItem {
        HOME, LIBRARY, MANAGE, ACCOUNT
    }

    /**
     * 不可变状态快照。Java 调用方使用 getter（如 [getSelectedItem]、[isLoading]、[isRecentRefreshing]），
     * Kotlin 调用方使用属性访问；data class 自动生成 equals/hashCode/toString/copy。
     *
     * 注意：boolean 属性以 `is` 前缀命名（[isLoading] / [isRecentRefreshing]），
     * Kotlin 会生成 `isXxx()` getter，与原 Java 实现签名一致，保证 Java 调用方零破坏。
     */
    data class LauncherState(
        val selectedItem: NavItem,
        val accountName: String,
        val accountMode: String,
        val syncStatus: String,
        val gameCount: Int,
        val totalPlayTime: String,
        val todayPlayTime: String,
        val favoriteItems: List<LauncherRepository.FavoriteItem>,
        val recentItems: List<LauncherRepository.RecentItem>,
        val isLoading: Boolean,
        val isRecentRefreshing: Boolean
    )

    private val repository = LauncherRepository(application)
    private val launcherState: MutableLiveData<LauncherState> = MutableLiveData(emptyState(true))
    /** 独立的数据域版本号，避免统计刷新与最近记录刷新相互淘汰。 */
    private val statsRefreshToken = AtomicInteger()
    private val recentRefreshToken = AtomicInteger()
    /** 删除开始即推进版本，使已读取删除前数据的请求不能再回写。 */
    private val dataMutationVersion = AtomicInteger()
    /** 按实际持锁完成删除的顺序编号，仅允许最新数据库快照提交到 UI。 */
    private val deleteCommitVersion = AtomicInteger()
    /** 使读取与删除按仓库操作顺序执行，防止读取旧列表在删除提交后回写。 */
    private val repositoryMutex = Mutex()
    /** 仅用于 UI 控制的 refreshing 显示，与状态写入校验分离。 */
    private val visibleRecentRefreshToken = AtomicInteger()

    /** Java 调用方通过 [getLauncherState] 观察；返回类型为 LiveData 以隐藏可变性。 */
    fun getLauncherState(): LiveData<LauncherState> = launcherState

    fun selectNavItem(item: NavItem?) {
        val selectedItem = item ?: NavItem.HOME
        val current = launcherState.value
        if (current == null) {
            launcherState.value = emptyState(false)
            return
        }
        launcherState.value = current.copy(selectedItem = selectedItem)
    }

    /**
     * 加载完整首页快照。只有明确展示收藏列表的调用方才传入 [includeFavorites]；
     * 默认关闭，避免普通首页与 HD 首页承担无用的收藏映射开销。
     */
    @JvmOverloads
    fun refresh(includeFavorites: Boolean = false) {
        val statsToken = statsRefreshToken.incrementAndGet()
        val recentToken = recentRefreshToken.incrementAndGet()
        val mutationVersion = dataMutationVersion.get()
        viewModelScope.launch {
            val snapshot = try {
                withContext(Dispatchers.IO) {
                    repositoryMutex.withLock {
                        repository.loadSnapshot(includeFavorites)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LauncherViewModel", "Failed to load snapshot", e)
                if (statsRefreshToken.get() == statsToken && recentRefreshToken.get() == recentToken &&
                    dataMutationVersion.get() == mutationVersion) {
                    val current = currentState()
                    launcherState.value = current.copy(isLoading = false, isRecentRefreshing = false)
                }
                return@launch
            }
            val current = currentState()
            val mutationUnchanged = dataMutationVersion.get() == mutationVersion
            val updateStats = mutationUnchanged && statsRefreshToken.get() == statsToken
            val updateRecent = mutationUnchanged && recentRefreshToken.get() == recentToken
            if (!updateStats && !updateRecent) return@launch
            launcherState.value = current.copy(
                accountName = if (updateStats) snapshot.accountName else current.accountName,
                accountMode = if (updateStats) snapshot.accountMode else current.accountMode,
                syncStatus = if (updateStats) snapshot.syncStatus else current.syncStatus,
                gameCount = if (updateStats) snapshot.gameCount else current.gameCount,
                totalPlayTime = if (updateStats) snapshot.totalPlayTime else current.totalPlayTime,
                todayPlayTime = if (updateStats) snapshot.todayPlayTime else current.todayPlayTime,
                favoriteItems = if (updateRecent && includeFavorites) {
                    ArrayList(snapshot.favoriteItems)
                } else {
                    current.favoriteItems
                },
                recentItems = if (updateRecent) ArrayList(snapshot.recentItems) else current.recentItems,
                isLoading = false,
                isRecentRefreshing = if (updateRecent) false else current.isRecentRefreshing
            )
        }
    }

    fun refreshStats() {
        val token = statsRefreshToken.incrementAndGet()
        val mutationVersion = dataMutationVersion.get()
        viewModelScope.launch {
            val snapshot = try {
                withContext(Dispatchers.IO) { repositoryMutex.withLock { repository.loadStatsSnapshot() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LauncherViewModel", "Failed to load stats snapshot", e)
                if (statsRefreshToken.get() == token && dataMutationVersion.get() == mutationVersion) {
                    val current = currentState()
                    launcherState.value = current.copy(isLoading = false)
                }
                return@launch
            }
            if (statsRefreshToken.get() != token || dataMutationVersion.get() != mutationVersion) return@launch
            val current = currentState()
            launcherState.value = current.copy(
                accountName = snapshot.accountName,
                accountMode = snapshot.accountMode,
                syncStatus = snapshot.syncStatus,
                gameCount = snapshot.gameCount,
                totalPlayTime = snapshot.totalPlayTime,
                todayPlayTime = snapshot.todayPlayTime,
                isLoading = false
            )
        }
    }

    /**
     * 拉取最近游戏记录。`showRefreshing=true` 时会先把状态切到 refreshing，
     * 并把当前 token 记录到 [visibleRecentRefreshToken]；后续若有新 token 进入，
     * 旧请求不会清除 refreshing 标志，避免 UI 抖动。
     *
     * 只有明确展示收藏列表的调用方才传入 `includeFavorites=true`。
     * `@JvmOverloads` 保留既有无参和单 Boolean（`showRefreshing`）重载，
     * 使 Java/Kotlin 旧调用保持兼容。
     */
    @JvmOverloads
    fun refreshRecentItems(
        showRefreshing: Boolean = false,
        includeFavorites: Boolean = false,
    ) {
        val token = recentRefreshToken.incrementAndGet()
        val mutationVersion = dataMutationVersion.get()
        if (showRefreshing) {
            setRecentRefreshing(true)
            visibleRecentRefreshToken.set(token)
        }
        viewModelScope.launch {
            val loadedLists: Pair<List<LauncherRepository.FavoriteItem>?, List<LauncherRepository.RecentItem>>? = try {
                withContext(Dispatchers.IO) {
                    repositoryMutex.withLock {
                        if (includeFavorites) {
                            val lists = repository.loadHomeListsSnapshot()
                            Pair(lists.favoriteItems, lists.recentItems)
                        } else {
                            Pair(null, repository.loadRecentItems())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LauncherViewModel", "Failed to load recent items", e)
                null
            }
            if (recentRefreshToken.get() != token || dataMutationVersion.get() != mutationVersion) return@launch
            val current = currentState()
            val keepRefreshing = current.isRecentRefreshing && visibleRecentRefreshToken.get() > token
            if (loadedLists != null) {
                val (favoriteItems, recentItems) = loadedLists
                launcherState.value = current.copy(
                    favoriteItems = favoriteItems?.let(::ArrayList) ?: current.favoriteItems,
                    recentItems = ArrayList(recentItems),
                    isLoading = false,
                    isRecentRefreshing = keepRefreshing
                )
            } else {
                // 加载失败时恢复 loading 状态，保留旧数据
                launcherState.value = current.copy(
                    isLoading = false,
                    isRecentRefreshing = keepRefreshing
                )
            }
        }
    }

    private fun setRecentRefreshing(refreshing: Boolean) {
        RxMainScheduler.post {
            val current = currentState()
            launcherState.value = current.copy(isRecentRefreshing = refreshing)
        }
    }

    /**
     * 软删除一条游玩动态并从当前列表中移除。
     *
     * 删除在 IO 线程执行；成功后在主线程更新 [launcherState]，
     * 从 recentItems 中过滤掉对应 sessionId 的条目并刷新统计数据。
     *
     * 删除与读取共用 [repositoryMutex]，确保读请求不会把删除前的快照回写到删除后状态。
     */
    fun deleteRecentItem(sessionId: Long) {
        if (sessionId <= 0) return
        dataMutationVersion.incrementAndGet()
        viewModelScope.launch {
            val deleteResult = try {
                withContext(Dispatchers.IO) { repositoryMutex.withLock {
                    val affected = LauncherRepositoryBridge.deletePlaySession(getApplication(), sessionId)
                    if (affected > 0) {
                        val statsAndRecent =
                            Pair(repository.loadStatsSnapshot(), repository.loadRecentItems())
                        Pair(statsAndRecent, deleteCommitVersion.incrementAndGet())
                    } else {
                        null
                    }
                } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LauncherViewModel", "Failed to delete recent item", e)
                null
            }
            if (deleteResult != null) {
                val (statsAndRecent, commitVersion) = deleteResult
                // 以实际完成数据库操作的顺序为准，旧快照不能覆盖后完成的删除。
                if (deleteCommitVersion.get() != commitVersion) return@launch
                val (snapshot, recentItems) = statsAndRecent
                val current = currentState()
                launcherState.value = current.copy(
                    recentItems = ArrayList(recentItems),
                    gameCount = snapshot.gameCount,
                    totalPlayTime = snapshot.totalPlayTime,
                    todayPlayTime = snapshot.todayPlayTime,
                    isRecentRefreshing = false
                )
            } else {
                // 删除失败/记录不存在同样要收束已被本次删除作废的下拉刷新状态。
                val current = currentState()
                if (current.isRecentRefreshing) {
                    launcherState.value = current.copy(isRecentRefreshing = false)
                }
            }
        }
    }

    private fun currentState(): LauncherState =
        launcherState.value ?: emptyState(loading = false)

    private fun emptyState(loading: Boolean): LauncherState = LauncherState(
        selectedItem = NavItem.HOME,
        accountName = getApplication<Application>().getString(com.core.R.string.home_local_player),
        accountMode = getApplication<Application>().getString(com.core.R.string.home_local_mode),
        syncStatus = getApplication<Application>().getString(com.core.R.string.repo_webdav_loading),
        gameCount = 0,
        totalPlayTime = "0s",
        todayPlayTime = "0s",
        favoriteItems = Collections.emptyList(),
        recentItems = Collections.emptyList(),
        isLoading = loading,
        isRecentRefreshing = false
    )
}
