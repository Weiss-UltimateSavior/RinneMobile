package com.yuki.yukihub.scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Partial scan output. Results remain usable even if the request is stopped. */
public final class ScanReport {
    public enum StopReason { COMPLETED, CANCELLED, NODE_LIMIT, DEADLINE, INVALID_ROOT }

    private final List<ScanResult> results = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int visitedNodes;
    private StopReason stopReason = StopReason.COMPLETED;

    public List<ScanResult> getResults() { return Collections.unmodifiableList(results); }
    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
    public int getVisitedNodes() { return visitedNodes; }
    public StopReason getStopReason() { return stopReason; }
    public boolean isPartial() { return stopReason != StopReason.COMPLETED; }

    void addResult(ScanResult result) { if (result != null) results.add(result); }
    void addError(String error) { if (error != null && !error.trim().isEmpty()) errors.add(error); }
    boolean tryVisit(ScanRequest request, String currentUri) {
        if (request.isCancelled()) { stopReason = StopReason.CANCELLED; return false; }
        if (request.getDeadlineAtElapsedMs() > 0
                && android.os.SystemClock.elapsedRealtime() >= request.getDeadlineAtElapsedMs()) {
            stopReason = StopReason.DEADLINE;
            return false;
        }
        if (request.getMaxNodes() > 0 && visitedNodes >= request.getMaxNodes()) {
            stopReason = StopReason.NODE_LIMIT;
            return false;
        }
        visitedNodes++;
        ScanRequest.ProgressListener listener = request.getProgressListener();
        if (listener != null) listener.onProgress(visitedNodes, results.size(), currentUri == null ? "" : currentUri);
        return true;
    }
    boolean shouldStop(ScanRequest request) { return !tryCheck(request); }
    private boolean tryCheck(ScanRequest request) {
        if (request.isCancelled()) { stopReason = StopReason.CANCELLED; return false; }
        if (request.getDeadlineAtElapsedMs() > 0
                && android.os.SystemClock.elapsedRealtime() >= request.getDeadlineAtElapsedMs()) {
            stopReason = StopReason.DEADLINE;
            return false;
        }
        return stopReason == StopReason.COMPLETED;
    }
    void setStopReason(StopReason reason) { if (reason != null) stopReason = reason; }
}
