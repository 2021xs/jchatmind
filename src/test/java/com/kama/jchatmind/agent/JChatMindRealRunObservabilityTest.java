package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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
                new ObjectMapper()
        );
        assignTaskIds(agentTaskMapper);
        assignStepIds(agentStepMapper);
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

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("done")
                        .toolCalls(List.of())
                        .build()
        )));
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .system(anyString())
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .call()
                .chatClientResponse())
                .thenReturn(new ChatClientResponse(chatResponse, java.util.Map.of()));

        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("assistant-message-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor conversationContextCompressor = mock(ConversationContextCompressor.class);
        when(conversationContextCompressor.check(anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0));

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
                List.of()
        );

        agent.run();

        ArgumentCaptor<AgentTask> taskInsert = ArgumentCaptor.forClass(AgentTask.class);
        ArgumentCaptor<AgentTask> taskUpdate = ArgumentCaptor.forClass(AgentTask.class);
        ArgumentCaptor<AgentStep> stepInsert = ArgumentCaptor.forClass(AgentStep.class);
        ArgumentCaptor<AgentStep> stepUpdate = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentTaskMapper).insert(taskInsert.capture());
        verify(agentTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(taskUpdate.capture());
        verify(agentStepMapper, org.mockito.Mockito.times(2)).insert(stepInsert.capture());
        verify(agentStepMapper, org.mockito.Mockito.atLeast(2)).updateById(stepUpdate.capture());

        AgentTask insertedTask = taskInsert.getValue();
        assertEquals(AgentTaskLogService.STATUS_RUNNING, insertedTask.getStatus());
        assertEquals("test-model", insertedTask.getModelName());
        assertEquals(20, insertedTask.getMaxSteps());
        assertNotNull(insertedTask.getTraceId());
        assertNotNull(insertedTask.getHeartbeatAt());

        List<AgentStep> insertedSteps = stepInsert.getAllValues();
        assertEquals(List.of("THINK", "FINISH"),
                insertedSteps.stream().map(AgentStep::getStepType).toList());
        assertEquals("test-model", insertedSteps.get(0).getModelName());

        AgentTask finalTask = lastWithStatus(taskUpdate.getAllValues(), AgentTaskLogService.STATUS_SUCCESS);
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, finalTask.getFinishReason());
        assertEquals(2, finalTask.getActualSteps());
        assertEquals(0, finalTask.getToolCallCount());
        assertNotNull(finalTask.getLatencyMs());

        List<AgentStep> successfulSteps = stepUpdate.getAllValues().stream()
                .filter(step -> AgentTaskLogService.STATUS_SUCCESS.equals(step.getStatus()))
                .toList();
        assertEquals(2, successfulSteps.size());
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, successfulSteps.get(0).getFinishReason());
        assertEquals(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, successfulSteps.get(1).getFinishReason());
        assertNotNull(successfulSteps.get(0).getLlmLatencyMs());
        assertNotNull(successfulSteps.get(0).getFinishedAt());
        assertNotNull(successfulSteps.get(1).getFinishedAt());
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
