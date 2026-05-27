package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.entity.AgentStep;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentRunFailureContext {
    private String taskId;
    private String sessionId;
    private AgentStep currentStep;
    private int actualSteps;
    private int toolCallCount;
}
