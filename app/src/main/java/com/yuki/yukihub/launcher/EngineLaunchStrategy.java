package com.yuki.yukihub.launcher;

import android.content.Context;

import com.yuki.yukihub.model.EngineType;

/**
 * Launch contract for a concrete emulator/engine integration.
 *
 * <p>Strategies must return {@code false} when their recognised integration
 * cannot be started.  The caller deliberately does not fall through to a
 * generic package launch in that case: this preserves the previous behaviour
 * for internal engines and prevents a failed game launch from opening an
 * emulator's home screen.</p>
 */
public interface EngineLaunchStrategy {
    /** The primary engine this strategy implements, for discovery and extension. */
    EngineType getEngineType();

    /** True only when this strategy owns the request's current package/protocol. */
    boolean supports(LaunchRequest request);

    /** Starts the request while preserving the engine's existing Intent contract. */
    boolean launch(Context context, LaunchRequest request);
}
