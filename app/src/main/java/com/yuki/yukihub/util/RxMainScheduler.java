package com.yuki.yukihub.util;

import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.Disposable;
import io.reactivex.android.schedulers.AndroidSchedulers;

/** Centralizes RxJava scheduling for work that must return to Android's main thread. */
public final class RxMainScheduler {
    private RxMainScheduler() { }

    public static Disposable post(Runnable action) {
        return AndroidSchedulers.mainThread().scheduleDirect(action);
    }

    public static Disposable postDelayed(Runnable action, long delayMs) {
        return AndroidSchedulers.mainThread().scheduleDirect(action, delayMs, TimeUnit.MILLISECONDS);
    }
}
