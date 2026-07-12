package com.yuki.yukihub.launcher;

import com.yuki.yukihub.model.EngineType;

/** Immutable input shared by the launcher strategy registry. */
public final class LaunchRequest {
    public final EngineType engineType;
    public final String packageName;
    public final String rootUri;
    public final String launchTarget;
    public final String winlatorLaunchMode;
    public final String gameHubLaunchMode;
    public final String gameHubLocalGameId;

    public LaunchRequest(EngineType engineType, String packageName, String rootUri,
                         String launchTarget, String winlatorLaunchMode,
                         String gameHubLaunchMode, String gameHubLocalGameId) {
        this.engineType = engineType == null ? EngineType.UNKNOWN : engineType;
        this.packageName = packageName == null ? "" : packageName.trim();
        this.rootUri = rootUri;
        this.launchTarget = launchTarget;
        this.winlatorLaunchMode = winlatorLaunchMode;
        this.gameHubLaunchMode = gameHubLaunchMode;
        this.gameHubLocalGameId = gameHubLocalGameId;
    }
}
