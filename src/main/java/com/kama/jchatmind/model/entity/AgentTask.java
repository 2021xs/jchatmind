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
public class AgentTask {
    private String id;
    private String sessionId;
    private String agentId;
    private String userMessageId;
    private String status;
    private String goal;
    private String finishReason;
    private String modelName;
    private Integer maxSteps;
    private Integer actualSteps;
    private Integer toolCallCount;
    private Long latencyMs;
    private String traceId;
    private LocalDateTime heartbeatAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
    private String errorMessage;
}
