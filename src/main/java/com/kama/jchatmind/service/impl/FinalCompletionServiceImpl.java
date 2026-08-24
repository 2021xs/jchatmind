package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.FinalCompletionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;

@Service
public class FinalCompletionServiceImpl implements FinalCompletionService {

    private final ChatMessageFacadeService chatMessageFacadeService;
    private final AgentTaskLogService agentTaskLogService;

    public FinalCompletionServiceImpl(ChatMessageFacadeService chatMessageFacadeService,
                                      AgentTaskLogService agentTaskLogService) {
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.agentTaskLogService = agentTaskLogService;
    }

    @Override
    @Transactional
    public FinalCompletionResult complete(FinalCompletionCommand command) {
        Assert.notNull(command, "Final completion command cannot be null");
        Assert.hasText(command.sessionId(), "Final completion sessionId cannot be empty");
        Assert.hasText(command.taskId(), "Final completion taskId cannot be empty");
        Assert.hasText(command.finalAnswer(), "Final completion answer cannot be empty");
        Assert.hasText(command.finalStepId(), "Final completion stepId cannot be empty");

        ChatMessageDTO finalMessage = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(command.finalAnswer())
                .sessionId(command.sessionId())
                .metadata(ChatMessageDTO.MetaData.builder().toolCalls(List.of()).build())
                .build();
        CreateChatMessageResponse created = chatMessageFacadeService.createChatMessage(finalMessage);
        Assert.notNull(created, "Final AssistantMessage persistence result cannot be null");
        Assert.hasText(created.getChatMessageId(), "Final AssistantMessage id cannot be empty");

        AgentTaskLogService.FinalLifecycleResult lifecycle = agentTaskLogService.completeFinalLifecycle(
                new AgentTaskLogService.FinalLifecycleCommand(
                        command.taskId(), command.finalStepId(), command.finalStepNo(),
                        command.finalStepSummary(), command.finalLlmLatencyMs(), command.finishStepNo(),
                        command.finishReason(), command.modelName(), command.actualSteps(), command.toolCallCount()));
        Assert.notNull(lifecycle, "Final lifecycle completion result cannot be null");

        return new FinalCompletionResult(created.getChatMessageId(), lifecycle.finalStepId(),
                lifecycle.finalStepNo(), lifecycle.finishStepId(), lifecycle.finishStepNo());
    }
}
