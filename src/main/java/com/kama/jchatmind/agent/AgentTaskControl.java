package com.kama.jchatmind.agent;

import reactor.core.Disposable;

import java.util.concurrent.Future;

public final class AgentTaskControl {
    public enum CancelResult {
        REQUESTED,
        ALREADY_REQUESTED,
        ALREADY_FINISHED
    }

    private final String taskId;
    private final String sessionId;
    private boolean cancellationRequested;
    private boolean terminal;
    private Future<?> currentToolFuture;
    private Disposable activeFinalStream;

    AgentTaskControl(String taskId, String sessionId) {
        this.taskId = taskId;
        this.sessionId = sessionId;
    }

    public String taskId() {
        return taskId;
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized CancelResult requestCancellation() {
        if (terminal) {
            return CancelResult.ALREADY_FINISHED;
        }
        if (cancellationRequested) {
            return CancelResult.ALREADY_REQUESTED;
        }
        cancellationRequested = true;
        if (currentToolFuture != null) {
            currentToolFuture.cancel(true);
        }
        if (activeFinalStream != null) {
            activeFinalStream.dispose();
        }
        return CancelResult.REQUESTED;
    }

    public synchronized boolean isCancellationRequested() {
        return cancellationRequested;
    }

    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new AgentTaskCancelledException(taskId);
        }
    }

    public synchronized void attachToolFuture(Future<?> future) {
        currentToolFuture = future;
        if (cancellationRequested && future != null) {
            future.cancel(true);
        }
    }

    public synchronized void detachToolFuture(Future<?> future) {
        if (currentToolFuture == future) {
            currentToolFuture = null;
        }
    }

    public synchronized void attachActiveStream(Disposable stream) {
        activeFinalStream = stream;
        if (cancellationRequested && stream != null) {
            stream.dispose();
        }
    }

    public synchronized void detachActiveStream(Disposable stream) {
        if (activeFinalStream == stream) {
            activeFinalStream = null;
        }
    }

    public synchronized boolean completeIfActive(Runnable completion) {
        if (terminal || cancellationRequested) {
            return false;
        }
        completion.run();
        terminal = true;
        return true;
    }

    public synchronized boolean runIfActive(Runnable action) {
        if (terminal || cancellationRequested) {
            return false;
        }
        action.run();
        return true;
    }

    public synchronized void completeCancellation(Runnable completion) {
        if (terminal) {
            return;
        }
        completion.run();
        terminal = true;
    }
}
