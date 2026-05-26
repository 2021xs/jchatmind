package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallLog {
    private String id;
    private String taskId;
    private String stepId;
    private String toolName;
    private String actualToolName;
    private String toolCallId;
    private String argumentsJson;
    private String resultSummary;
    private String status;
    private Long latencyMs;
    private String errorMessage;
    private String errorType;
    private Boolean blockedByPolicy;
    private Boolean argumentTruncated;
    private Boolean resultTruncated;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
