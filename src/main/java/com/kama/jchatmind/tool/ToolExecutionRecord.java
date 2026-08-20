package com.kama.jchatmind.tool;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolExecutionRecord {
    private String toolCallId;
    private String actualToolName;
    private String canonicalToolName;
    private String toolCallLogId;
    private long startedAtMillis;
    private boolean argumentTruncated;
    private boolean resultGuardApplied;
    private int originalResultChars;
    private int storedResultChars;
    private int maxResultChars;
    private boolean runtimeResultTruncated;
    private boolean terminalRecorded;
}
