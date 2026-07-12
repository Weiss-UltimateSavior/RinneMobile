package com.yuki.yukihub.scanner;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controls one directory scan. A request is deliberately independent from UI classes so the
 * same limits can be used by Launcher, background sync, and future batch importers.
 */
public final class ScanRequest {
    public static final int DEFAULT_MAX_NODES = 10_000;
    public static final long DEFAULT_DEADLINE_MS = 2L * 60L * 1000L;

    public interface ProgressListener {
        void onProgress(int visitedNodes, int foundGames, String currentUri);
    }

    private final int maxDepth;
    private final int maxNodes;
    private final long deadlineAtElapsedMs;
    private final AtomicBoolean cancelled;
    private final ProgressListener progressListener;

    private ScanRequest(Builder builder) {
        maxDepth = builder.maxDepth;
        maxNodes = builder.maxNodes;
        deadlineAtElapsedMs = builder.deadlineAtElapsedMs;
        cancelled = builder.cancelled == null ? new AtomicBoolean(false) : builder.cancelled;
        progressListener = builder.progressListener;
    }

    public static ScanRequest defaults(int maxDepth) {
        return new Builder(maxDepth)
                .setMaxNodes(DEFAULT_MAX_NODES)
                .setDeadlineAfterMs(DEFAULT_DEADLINE_MS)
                .build();
    }

    public int getMaxDepth() { return maxDepth; }
    public int getMaxNodes() { return maxNodes; }
    public long getDeadlineAtElapsedMs() { return deadlineAtElapsedMs; }
    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); }
    ProgressListener getProgressListener() { return progressListener; }

    public static final class Builder {
        private final int maxDepth;
        private int maxNodes = DEFAULT_MAX_NODES;
        private long deadlineAtElapsedMs;
        private AtomicBoolean cancelled;
        private ProgressListener progressListener;

        public Builder(int maxDepth) { this.maxDepth = maxDepth; }

        /** A value <= 0 means no node-count limit. */
        public Builder setMaxNodes(int value) { maxNodes = value; return this; }
        /** A value <= 0 means no deadline. */
        public Builder setDeadlineAfterMs(long value) {
            deadlineAtElapsedMs = value <= 0 ? 0 : android.os.SystemClock.elapsedRealtime() + value;
            return this;
        }
        public Builder setDeadlineAtElapsedMs(long value) { deadlineAtElapsedMs = value; return this; }
        public Builder setCancellationFlag(AtomicBoolean value) { cancelled = value; return this; }
        public Builder setProgressListener(ProgressListener value) { progressListener = value; return this; }
        public ScanRequest build() { return new ScanRequest(this); }
    }
}
