package com.kama.jchatmind.agent;

import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.service.AgentTaskLogService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class AgentRunFailureHandler {
    private final AgentTaskLogService agentTaskLogService;
    private final AgentEventPublisher eventPublisher;

    public void handle(AgentRunFailureContext context, Exception error) {
        String errorMessage = error == null ? null : error.getMessage();
        AgentStep currentStep = context.getCurrentStep();
        if (currentStep != null) {
            agentTaskLogService.failStepAndTask(currentStep.getId(), context.getTaskId(),
                    errorMessage, context.getActualSteps(), context.getToolCallCount());
        } else {
            agentTaskLogService.failTask(context.getTaskId(),
                    errorMessage, context.getActualSteps(), context.getToolCallCount());
        }
        eventPublisher.publish(context.getTaskId(), context.getSessionId(), AgentSseEvent.Type.ERROR, payload(
                "status", AgentTaskLogService.STATUS_FAILED,
                "stepId", currentStep == null ? null : currentStep.getId(),
                "stepNo", currentStep == null ? null : currentStep.getStepNo(),
                "stepType", currentStep == null ? null : currentStep.getStepType(),
                "finishReason", AgentTaskLogService.FINISH_REASON_ERROR,
                "errorMessage", truncate(errorMessage)
        ));
        eventPublisher.complete(context.getSessionId(), context.getTaskId());
        log.error("Error running agent", error);
    }

    private Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return payload;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 4000) {
            return value;
        }
        return value.substring(0, 3968) + "\n...[truncated]";
    }
}
