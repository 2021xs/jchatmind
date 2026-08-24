package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.ToolCallLog;

import java.util.List;

record AgentTaskTrajectory(
        AgentTask task,
        List<AgentStep> steps,
        List<ToolCallLog> toolCalls,
        String finalAnswer) {

    AgentTaskTrajectory {
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
