package com.apps;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.RxMainScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LauncherViewModel extends AndroidViewModel {
    public enum NavItem {
        HOME,
        LIBRARY,
        MANAGE,
        ACCOUNT
    }

    public static final class LauncherState {
        private final NavItem selectedItem;
        private final String accountName;
        private final String accountMode;
        private final String syncStatus;
        private final int gameCount;
        private final String totalPlayTime;
        private final String todayPlayTime;
        private final List<LauncherRepository.RecentItem> recentItems;
        private final boolean loading;
        private final boolean recentRefreshing;

        LauncherState(
                NavItem selectedItem,
                String accountName,
                String accountMode,
                String syncStatus,
                int gameCount,
                String totalPlayTime,
                String todayPlayTime,
                List<LauncherRepository.RecentItem> recentItems,
                boolean loading,
                boolean recentRefreshing
        ) {
            this.selectedItem = selectedItem;
            this.accountName = accountName;
            this.accountMode = accountMode;
            this.syncStatus = syncStatus;
            this.gameCount = gameCount;
            this.totalPlayTime = totalPlayTime;
            this.todayPlayTime = todayPlayTime;
            this.recentItems = recentItems == null ? Collections.emptyList() : recentItems;
            this.loading = loading;
            this.recentRefreshing = recentRefreshing;
        }

        public NavItem getSelectedItem() {
            return selectedItem;
        }

        public String getAccountName() {
            return accountName;
        }

        public String getAccountMode() {
            return accountMode;
        }

        public String getSyncStatus() {
            return syncStatus;
        }

        public int getGameCount() {
            return gameCount;
        }

        public String getTotalPlayTime() {
            return totalPlayTime;
        }

        public String getTodayPlayTime() {
            return todayPlayTime;
        }

        public List<LauncherRepository.RecentItem> getRecentItems() {
            return recentItems;
        }

        public boolean isLoading() {
            return loading;
        }

        public boolean isRecentRefreshing() {
            return recentRefreshing;
        }
    }

    private final LauncherRepository repository;
    private final MutableLiveData<LauncherState> launcherState = new MutableLiveData<>(emptyState(true));
    private final AtomicInteger recentRefreshToken = new AtomicInteger();
    private volatile NavItem selectedItem = NavItem.HOME;
    private volatile int visibleRecentRefreshToken = 0;
    private volatile boolean recentItemsLoadStarted;
    private volatile boolean recentItemsLoaded;

    public LauncherViewModel(@NonNull Application application) {
        super(application);
        repository = new LauncherRepository(application);
    }

    public LiveData<LauncherState> getLauncherState() {
        return launcherState;
    }

    public void selectNavItem(NavItem item) {
        selectedItem = item == null ? NavItem.HOME : item;
        LauncherState current = launcherState.getValue();
        if (current == null) {
            launcherState.setValue(emptyState(false));
            return;
        }
        launcherState.setValue(copyWithSelected(current, selectedItem));
    }

    public void refresh() {
        AppExecutors.runOnSingle(() -> {
            LauncherRepository.LauncherSnapshot snapshot = repository.loadSnapshot();
            RxMainScheduler.post(() -> launcherState.setValue(new LauncherState(
                    selectedItem,
                    snapshot.accountName,
                    snapshot.accountMode,
                    snapshot.syncStatus,
                    snapshot.gameCount,
                    snapshot.totalPlayTime,
                    snapshot.todayPlayTime,
                    new ArrayList<>(snapshot.recentItems),
                    false,
                    false
            )));
        });
    }

    public void refreshStats() {
        AppExecutors.runOnSingle(() -> {
            LauncherRepository.StatsSnapshot snapshot = repository.loadStatsSnapshot();
            RxMainScheduler.post(() -> {
                LauncherState current = currentState();
                launcherState.setValue(new LauncherState(
                        selectedItem,
                        snapshot.accountName,
                        snapshot.accountMode,
                        snapshot.syncStatus,
                        snapshot.gameCount,
                        snapshot.totalPlayTime,
                        snapshot.todayPlayTime,
                        current.getRecentItems(),
                        false,
                        current.isRecentRefreshing()
                ));
            });
        });
    }

    public void refreshRecentItems() {
        refreshRecentItems(false);
    }

    public void refreshRecentItemsIfNeeded() {
        boolean shouldRefresh;
        synchronized (this) {
            shouldRefresh = !recentItemsLoaded && !recentItemsLoadStarted;
            if (shouldRefresh) recentItemsLoadStarted = true;
        }
        if (shouldRefresh) refreshRecentItems(false);
    }

    public void refreshRecentItems(boolean showRefreshing) {
        int token = recentRefreshToken.incrementAndGet();
        synchronized (this) {
            recentItemsLoadStarted = true;
        }
        if (showRefreshing) setRecentRefreshing(true);
        if (showRefreshing) visibleRecentRefreshToken = token;
        AppExecutors.runOnSingle(() -> {
            List<LauncherRepository.RecentItem> recentItems = repository.loadRecentItems();
            RxMainScheduler.post(() -> {
                LauncherState current = currentState();
                synchronized (LauncherViewModel.this) {
                    recentItemsLoaded = true;
                    recentItemsLoadStarted = false;
                }
                boolean keepRefreshing = current.isRecentRefreshing() && visibleRecentRefreshToken > token;
                launcherState.setValue(new LauncherState(
                        selectedItem,
                        current.getAccountName(),
                        current.getAccountMode(),
                        current.getSyncStatus(),
                        current.getGameCount(),
                        current.getTotalPlayTime(),
                        current.getTodayPlayTime(),
                        new ArrayList<>(recentItems),
                        false,
                        keepRefreshing
                ));
            });
        });
    }

    private void setRecentRefreshing(boolean refreshing) {
        RxMainScheduler.post(() -> {
            LauncherState current = currentState();
            launcherState.setValue(new LauncherState(
                    selectedItem,
                    current.getAccountName(),
                    current.getAccountMode(),
                    current.getSyncStatus(),
                    current.getGameCount(),
                    current.getTotalPlayTime(),
                    current.getTodayPlayTime(),
                    current.getRecentItems(),
                    current.isLoading(),
                    refreshing
            ));
        });
    }

    private LauncherState copyWithSelected(LauncherState current, NavItem item) {
        return new LauncherState(
                item,
                current.getAccountName(),
                current.getAccountMode(),
                current.getSyncStatus(),
                current.getGameCount(),
                current.getTotalPlayTime(),
                current.getTodayPlayTime(),
                current.getRecentItems(),
                current.isLoading(),
                current.isRecentRefreshing()
        );
    }

    private LauncherState currentState() {
        LauncherState current = launcherState.getValue();
        return current == null ? emptyState(false) : current;
    }

    private static LauncherState emptyState(boolean loading) {
        return new LauncherState(
                NavItem.HOME,
                "本地玩家",
                "本地模式",
                "WebDAV 状态读取中",
                0,
                "0s",
                "0s",
                Collections.emptyList(),
                loading,
                false
        );
    }
}
