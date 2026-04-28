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
    private String argumentsJson;
    private String resultSummary;
    private String status;
    private Long latencyMs;
    private String errorMessage;
    private LocalDateTime createdAt;
}
