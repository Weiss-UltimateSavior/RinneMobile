package com.akira.tyranoemu.remote;

import T3.r;

public final class Kirikiroid134 extends r {
    @Override
    public void onLoadNativeLibraries() {
        System.loadLibrary("ffmpeg");
        System.loadLibrary("game134");
        System.loadLibrary("kirikiroid3");
        super.onLoadNativeLibraries();
    }

    @Override
    public String soName() {
        return "libgame134.so";
    }
}
