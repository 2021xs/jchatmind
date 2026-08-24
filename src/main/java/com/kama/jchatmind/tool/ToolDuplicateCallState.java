package com.kama.jchatmind.tool;

public class ToolDuplicateCallState {
    private String lastDuplicateKey;
    private int consecutiveCount;
    private boolean rejectionIssued;
    private boolean hardStopRequested;

    public Observation observe(String duplicateKey, int maxConsecutiveSameCalls) {
        if (!duplicateKey.equals(lastDuplicateKey)) {
            lastDuplicateKey = duplicateKey;
            consecutiveCount = 1;
            rejectionIssued = false;
            return new Observation(Action.ALLOWED, consecutiveCount);
        }

        consecutiveCount++;
        if (consecutiveCount <= maxConsecutiveSameCalls) {
            return new Observation(Action.ALLOWED, consecutiveCount);
        }
        if (!rejectionIssued) {
            rejectionIssued = true;
            return new Observation(Action.REJECTED, consecutiveCount);
        }
        hardStopRequested = true;
        return new Observation(Action.HARD_STOP, consecutiveCount);
    }

    public void resetSequence() {
        lastDuplicateKey = null;
        consecutiveCount = 0;
        rejectionIssued = false;
    }

    public void reset() {
        resetSequence();
        hardStopRequested = false;
    }

    public boolean isHardStopRequested() {
        return hardStopRequested;
    }

    public enum Action {
        ALLOWED,
        REJECTED,
        HARD_STOP
    }

    public record Observation(Action action, int consecutiveCount) {
    }
}
