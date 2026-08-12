package com.akira.tyranoemu.remote;

import android.util.Log;

import com.core.nativeplugin.NativeLibraryLoader;

public final class Kirikiroid139 extends KirikiroidLauncherBaseActivity {
    @Override
    public void onLoadNativeLibraries() {
        String gameLibrary = NativeLibraryLoader.loadKirikiroid139(this);
        if (gameLibrary == null) {
            Log.e("Kirikiroid2", "Kirikiroid2 plugin missing or invalid for libgame.so");
            return;
        }
        setResolvedGameLibrary(gameLibrary);
        System.loadLibrary("krkr_bridge");
        super.onLoadNativeLibraries();
    }

    @Override
    public String soName() {
        return "libgame.so";
    }
}
