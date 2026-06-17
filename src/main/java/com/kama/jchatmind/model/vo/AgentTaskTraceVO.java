package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AgentTaskTraceVO {
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
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private List<AgentStepTraceVO> steps;
    private List<ToolCallTraceVO> toolCalls;
}
