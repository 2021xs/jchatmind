package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CancelAgentTaskResponse;

public interface AgentTaskLifecycleService {
    ReservedTask reserve(String sessionId,
                         String agentId,
                         String modelName,
                         int maxSteps,
                         String traceId,
                         CreateChatMessageRequest userMessageRequest);

    CancelAgentTaskResponse cancel(String taskId, String sessionId);

    record ReservedTask(AgentTask task, String userMessageId) {
    }
}
