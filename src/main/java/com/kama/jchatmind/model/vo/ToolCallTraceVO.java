package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ToolCallTraceVO {
    private String id;
    private String taskId;
    private String stepId;
    private String toolName;
    private String actualToolName;
    private String toolCallId;
    private String resultSummary;
    private String status;
    private Long latencyMs;
    private String errorMessage;
    private String errorType;
    private Boolean blockedByPolicy;
    private Boolean argumentTruncated;
    private Boolean resultTruncated;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
