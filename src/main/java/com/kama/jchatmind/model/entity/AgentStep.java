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
public class AgentStep {
    private String id;
    private String taskId;
    private Integer stepNo;
    private String stepType;
    private String status;
    private String inputSummary;
    private String outputSummary;
    private Long latencyMs;
    private String modelName;
    private Long llmLatencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private String finishReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
    private String errorMessage;
}
