package com.kama.jchatmind.benchmark.context;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;

import java.util.List;

record ContextLifecycleCaseExecution(
        ContextLifecycleBenchmarkCase benchmarkCase,
        int repeatIndex,
        String sessionId,
        AgentTask task,
        List<AgentStep> steps,
        List<ToolCallLog> toolCalls,
        List<ChatMessageDTO> sessionMessages,
        ContextLifecycleObservationCollector.CaseCapture capture,
        String executionFailure) {

    ContextLifecycleCaseExecution {
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        sessionMessages = sessionMessages == null ? List.of() : List.copyOf(sessionMessages);
    }
}
