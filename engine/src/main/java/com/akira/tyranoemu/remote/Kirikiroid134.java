package com.akira.tyranoemu.remote;

import android.util.Log;

import com.core.nativeplugin.NativeLibraryLoader;

public final class Kirikiroid134 extends KirikiroidLauncherBaseActivity {
    @Override
    public void onLoadNativeLibraries() {
        String gameLibrary = NativeLibraryLoader.loadKirikiroid134(this);
        if (gameLibrary == null) {
            Log.e("Kirikiroid2", "Kirikiroid2 plugin missing or invalid for libgame134.so");
            return;
        }
        setResolvedGameLibrary(gameLibrary);
        System.loadLibrary("krkr_bridge");
        super.onLoadNativeLibraries();
    }

    @Override
    public String soName() {
        return "libgame134.so";
    }
}
