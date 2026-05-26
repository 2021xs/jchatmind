package com.kama.jchatmind.agent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentExecutionContext {
    private String taskId;
    private String currentStepId;
    private Integer stepNo;
    private String traceId;
    private String sessionId;
    private String agentId;
    private String modelName;
    private Integer maxSteps;
}
