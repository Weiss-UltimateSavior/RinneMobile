package com.akira.tyranoemu.remote;

import T3.r;

public final class Kirikiroid139 extends r {
    @Override
    public void onLoadNativeLibraries() {
        System.loadLibrary("SDL2");
        System.loadLibrary("ffmpeg");
        System.loadLibrary("game");
        System.loadLibrary("kirikiroid3");
        System.loadLibrary("krkr_exit_guard");
        super.onLoadNativeLibraries();
    }

    @Override
    public String soName() {
        return "libgame.so";
    }
}
