package com.apps.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.yuki.yukihub.util.AppExecutors
import com.yuki.yukihub.util.RxMainScheduler
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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
        val recentItems: List<LauncherRepository.RecentItem>,
        val isLoading: Boolean,
        val isRecentRefreshing: Boolean
    )

    private val repository = LauncherRepository(application)
    private val launcherState: MutableLiveData<LauncherState> = MutableLiveData(emptyState(true))
    private val recentRefreshToken = AtomicInteger()

    @Volatile private var selectedItem: NavItem = NavItem.HOME
    @Volatile private var visibleRecentRefreshToken = 0
    @Volatile private var recentItemsLoadStarted = false
    @Volatile private var recentItemsLoaded = false

    /** Java 调用方通过 [getLauncherState] 观察；返回类型为 LiveData 以隐藏可变性。 */
    fun getLauncherState(): LiveData<LauncherState> = launcherState

    fun selectNavItem(item: NavItem?) {
        selectedItem = item ?: NavItem.HOME
        val current = launcherState.value
        if (current == null) {
            launcherState.value = emptyState(false)
            return
        }
        launcherState.value = current.copy(selectedItem = selectedItem)
    }

    fun refresh() {
        AppExecutors.runOnSingle {
            val snapshot = repository.loadSnapshot()
            RxMainScheduler.post {
                launcherState.value = LauncherState(
                    selectedItem = selectedItem,
                    accountName = snapshot.accountName,
                    accountMode = snapshot.accountMode,
                    syncStatus = snapshot.syncStatus,
                    gameCount = snapshot.gameCount,
                    totalPlayTime = snapshot.totalPlayTime,
                    todayPlayTime = snapshot.todayPlayTime,
                    recentItems = ArrayList(snapshot.recentItems),
                    isLoading = false,
                    isRecentRefreshing = false
                )
            }
        }
    }

    fun refreshStats() {
        AppExecutors.runOnSingle {
            val snapshot = repository.loadStatsSnapshot()
            RxMainScheduler.post {
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
    }

    fun refreshRecentItemsIfNeeded() {
        val shouldRefresh: Boolean
        synchronized(this) {
            shouldRefresh = !recentItemsLoaded && !recentItemsLoadStarted
            if (shouldRefresh) recentItemsLoadStarted = true
        }
        if (shouldRefresh) refreshRecentItems(showRefreshing = false)
    }

    /**
     * 拉取最近游戏记录。`showRefreshing=true` 时会先把状态切到 refreshing，
     * 并把当前 token 记录到 [visibleRecentRefreshToken]；后续若有新 token 进入，
     * 旧请求不会清除 refreshing 标志，避免 UI 抖动。
     *
     * `@JvmOverloads` 让 Java 调用方仍可用无参重载 `refreshRecentItems()`，
     * 与原 Java 实现的两个 public 方法签名兼容。
     */
    @JvmOverloads
    fun refreshRecentItems(showRefreshing: Boolean = false) {
        val token = recentRefreshToken.incrementAndGet()
        synchronized(this) {
            recentItemsLoadStarted = true
        }
        if (showRefreshing) {
            setRecentRefreshing(true)
            visibleRecentRefreshToken = token
        }
        AppExecutors.runOnSingle {
            val recentItems = repository.loadRecentItems()
            RxMainScheduler.post {
                val current = currentState()
                synchronized(this@LauncherViewModel) {
                    recentItemsLoaded = true
                    recentItemsLoadStarted = false
                }
                val keepRefreshing = current.isRecentRefreshing && visibleRecentRefreshToken > token
                launcherState.value = current.copy(
                    recentItems = ArrayList(recentItems),
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

    private fun currentState(): LauncherState =
        launcherState.value ?: emptyState(loading = false)

    private fun emptyState(loading: Boolean): LauncherState = LauncherState(
        selectedItem = NavItem.HOME,
        accountName = "本地玩家",
        accountMode = "本地模式",
        syncStatus = "WebDAV 状态读取中",
        gameCount = 0,
        totalPlayTime = "0s",
        todayPlayTime = "0s",
        recentItems = Collections.emptyList(),
        isLoading = loading,
        isRecentRefreshing = false
    )
}
