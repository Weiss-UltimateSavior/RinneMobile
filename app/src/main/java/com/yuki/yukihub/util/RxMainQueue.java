package com.yuki.yukihub.util;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.disposables.Disposable;

/** RxJava-backed main-thread queue with cancellable delayed tasks. */
public final class RxMainQueue {
    private final Map<Runnable, List<Disposable>> delayedTasks = new IdentityHashMap<>();

    public void post(Runnable action) {
        RxMainScheduler.post(action);
    }

    public void postDelayed(Runnable action, long delayMs) {
        if (action == null) return;
        AtomicReference<Disposable> reference = new AtomicReference<>();
        Disposable disposable = RxMainScheduler.postDelayed(() -> {
            try {
                action.run();
            } finally {
                removeTracked(action, reference.get());
            }
        }, delayMs);
        reference.set(disposable);
        synchronized (delayedTasks) {
            delayedTasks.computeIfAbsent(action, ignored -> new ArrayList<>()).add(disposable);
        }
    }

    public void removeCallbacks(Runnable action) {
        if (action == null) return;
        List<Disposable> disposables;
        synchronized (delayedTasks) {
            disposables = delayedTasks.remove(action);
        }
        if (disposables != null) for (Disposable disposable : disposables) disposable.dispose();
    }

    private void removeTracked(Runnable action, Disposable disposable) {
        synchronized (delayedTasks) {
            List<Disposable> disposables = delayedTasks.get(action);
            if (disposables == null) return;
            disposables.remove(disposable);
            if (disposables.isEmpty()) delayedTasks.remove(action);
        }
    }
}
