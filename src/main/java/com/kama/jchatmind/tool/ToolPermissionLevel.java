package com.kama.jchatmind.tool;

public enum ToolPermissionLevel {
    SAFE_READ(0),
    SENSITIVE_READ(1),
    WRITE(2),
    DANGEROUS(3);

    private final int riskRank;

    ToolPermissionLevel(int riskRank) {
        this.riskRank = riskRank;
    }

    public int getRiskRank() {
        return riskRank;
    }
}
