package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AgentStepTraceVO {
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
    private String errorMessage;
}
