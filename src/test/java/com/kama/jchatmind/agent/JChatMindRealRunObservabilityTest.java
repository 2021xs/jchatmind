package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AgentObservabilityProperties;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.service.impl.AgentTaskLogServiceImpl;
import com.kama.jchatmind.service.impl.FinalCompletionServiceImpl;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindRealRunObservabilityTest {

    @Test
    void runWithoutToolCallsWritesTaskThinkAndFinishLogs() {
        AgentTaskMapper agentTaskMapper = mock(AgentTaskMapper.class);
        AgentStepMapper agentStepMapper = mock(AgentStepMapper.class);
        ToolCallLogMapper toolCallLogMapper = mock(ToolCallLogMapper.class);
        AgentTaskLogService logService = new AgentTaskLogServiceImpl(
                agentTaskMapper,
                agentStepMapper,
                toolCallLogMapper,
                new ObjectMapper(),
                new AgentObservabilityProperties()
        );
        assignTaskIds(agentTaskMapper);
        assignStepIds(agentStepMapper);
        when(agentTaskMapper.updateTerminalIfRunning(any(AgentTask.class))).thenReturn(1);
        when(agentStepMapper.updateById(any(AgentStep.class))).thenReturn(1);
        when(agentTaskMapper.selectById("task-1")).thenReturn(AgentTask.builder()
                .id("task-1")
                .startedAt(java.time.LocalDateTime.now().minusSeconds(1))
                .build());
        when(agentStepMapper.selectById("step-1")).thenReturn(AgentStep.builder()
                .id("step-1")
                .taskId("task-1")
                .startedAt(java.time.LocalDateTime.now().minusNanos(500_000_000))
                .build());
        when(agentStepMapper.selectById("step-2")).thenReturn(AgentStep.builder()
                .id("step-2")
                .taskId("task-1")
                .startedAt(java.time.LocalDateTime.now().minusNanos(100_000_000))
                .build());

        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("done")
                        .toolCalls(List.of())
                        .build()
        )));
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatClientResponse()).thenReturn(new ChatClientResponse(chatResponse, java.util.Map.of()));

        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("assistant-message-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor conversationContextCompressor = mock(ConversationContextCompressor.class);
        when(conversationContextCompressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

        JChatMind agent = new JChatMind(
                "22222222-2222-2222-2222-222222222222",
                "test-model",
                "test-agent",
                "test",
                "system",
                chatClient,
                20,
                List.of(new UserMessage("hello")),
                List.of(),
                List.of(),
                "11111111-1111-1111-1111-111111111111",
                mock(SseService.class),
                mock(ToolExecutionService.class),
                chatMessageFacadeService,
                mock(ChatMessageConverter.class),
                logService,
                conversationContextCompressor,
                "33333333-3333-3333-3333-333333333333",
                List.of(),
                new ToolCorrectionProperties(),
                new ToolFailureClassifier(),
                mock(ToolCallBatchExecutor.class)
        );
        JChatMindSafeFinalTestSupport.configureStream(requestSpec, Flux.just(ChatResponse.builder()
                .generations(List.of(new Generation(
                        AssistantMessage.builder().content("validated final answer").build(),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())))
                .build()));
        agent.setFinalCompletionService(new FinalCompletionServiceImpl(chatMessageFacadeService, logService));

        agent.run();

        ArgumentCaptor<AgentTask> taskInsert = ArgumentCaptor.forClass(AgentTask.class);
        ArgumentCaptor<AgentTask> taskUpdate = ArgumentCaptor.forClass(AgentTask.class);
        ArgumentCaptor<AgentStep> stepInsert = ArgumentCaptor.forClass(AgentStep.class);
        ArgumentCaptor<AgentStep> stepUpdate = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentTaskMapper).insert(taskInsert.capture());
        verify(agentTaskMapper, org.mockito.Mockito.atLeastOnce()).updateTerminalIfRunning(taskUpdate.capture());
        verify(agentStepMapper, org.mockito.Mockito.times(3)).insert(stepInsert.capture());
        verify(agentStepMapper, org.mockito.Mockito.atLeast(3)).updateById(stepUpdate.capture());

        AgentTask insertedTask = taskInsert.getValue();
        assertEquals(AgentTaskLogService.STATUS_RUNNING, insertedTask.getStatus());
        assertEquals("test-model", insertedTask.getModelName());
        assertEquals(20, insertedTask.getMaxSteps());
        assertNotNull(insertedTask.getTraceId());
        assertNotNull(insertedTask.getHeartbeatAt());

        List<AgentStep> insertedSteps = stepInsert.getAllValues();
        assertEquals(List.of("THINK", "FINAL_SYNTHESIS", "FINISH"),
                insertedSteps.stream().map(AgentStep::getStepType).toList());
        assertEquals("test-model", insertedSteps.get(0).getModelName());

        AgentTask finalTask = lastWithStatus(taskUpdate.getAllValues(), AgentTaskLogService.STATUS_SUCCESS);
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, finalTask.getFinishReason());
        assertEquals(3, finalTask.getActualSteps());
        assertEquals(0, finalTask.getToolCallCount());
        assertNotNull(finalTask.getLatencyMs());

        List<AgentStep> successfulSteps = stepUpdate.getAllValues().stream()
                .filter(step -> AgentTaskLogService.STATUS_SUCCESS.equals(step.getStatus()))
                .toList();
        assertEquals(3, successfulSteps.size());
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, successfulSteps.get(0).getFinishReason());
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, successfulSteps.get(1).getFinishReason());
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, successfulSteps.get(2).getFinishReason());
        assertNotNull(successfulSteps.get(0).getLlmLatencyMs());
        assertNotNull(successfulSteps.get(0).getFinishedAt());
        assertNotNull(successfulSteps.get(1).getFinishedAt());
        assertNotNull(successfulSteps.get(2).getFinishedAt());
    }

    private void assignTaskIds(AgentTaskMapper mapper) {
        when(mapper.insert(any(AgentTask.class))).thenAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setId("task-1");
            return 1;
        });
    }

    private void assignStepIds(AgentStepMapper mapper) {
        AtomicInteger sequence = new AtomicInteger(1);
        when(mapper.insert(any(AgentStep.class))).thenAnswer(invocation -> {
            AgentStep step = invocation.getArgument(0);
            step.setId("step-" + sequence.getAndIncrement());
            return 1;
        });
    }

    private AgentTask lastWithStatus(List<AgentTask> tasks, String status) {
        List<AgentTask> matches = new ArrayList<>(tasks.stream()
                .filter(task -> status.equals(task.getStatus()))
                .toList());
        return matches.get(matches.size() - 1);
    }
}
