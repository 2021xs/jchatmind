package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.AgentTaskControl;
import com.kama.jchatmind.agent.AgentTaskRuntimeRegistry;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CancelAgentTaskResponse;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLifecycleService;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@AllArgsConstructor
public class AgentTaskLifecycleServiceImpl implements AgentTaskLifecycleService {
    private final AgentTaskLogService agentTaskLogService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final AgentTaskRuntimeRegistry runtimeRegistry;

    @Override
    @Transactional
    public ReservedTask reserve(String sessionId,
                                String agentId,
                                String modelName,
                                int maxSteps,
                                String traceId,
                                CreateChatMessageRequest userMessageRequest) {
        AgentTask task = agentTaskLogService.startTask(sessionId, agentId, null,
                "chat session agent run", modelName, maxSteps, traceId);
        CreateChatMessageResponse userMessage = chatMessageFacadeService.agentCreateChatMessage(userMessageRequest);
        agentTaskLogService.bindUserMessage(task.getId(), userMessage.getChatMessageId());
        task.setUserMessageId(userMessage.getChatMessageId());
        return new ReservedTask(task, userMessage.getChatMessageId());
    }

    @Override
    public CancelAgentTaskResponse cancel(String taskId, String sessionId) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(sessionId)) {
            throw new BizException("taskId 和 conversationId 必填。");
        }
        AgentTask task = agentTaskLogService.getTask(taskId);
        if (task == null) {
            throw new BizException("TASK_NOT_FOUND");
        }
        if (!sessionId.equals(task.getSessionId())) {
            throw new BizException("TASK_SESSION_MISMATCH");
        }
        if (!AgentTaskLogService.STATUS_RUNNING.equals(task.getStatus())) {
            return response(taskId, "TASK_ALREADY_FINISHED");
        }
        AgentTaskControl control = runtimeRegistry.find(taskId)
                .orElseThrow(() -> new BizException("TASK_RUNTIME_NOT_ACTIVE"));
        AgentTaskControl.CancelResult result = control.requestCancellation();
        return response(taskId, switch (result) {
            case REQUESTED -> "CANCELLATION_REQUESTED";
            case ALREADY_REQUESTED -> "ALREADY_CANCELLING";
            case ALREADY_FINISHED -> "TASK_ALREADY_FINISHED";
        });
    }

    private CancelAgentTaskResponse response(String taskId, String status) {
        return CancelAgentTaskResponse.builder().taskId(taskId).status(status).build();
    }
}
