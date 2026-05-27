package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindToolCorrectionTest {

    @Test
    void correctableToolFailureIsFedBackToModelWithoutFailingTask() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "searchProjectCode",
                                "{\"repoId\":\"repo-1\"}"
                        )))
                        .build()
        )));
        ChatResponse finalResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("fixed").toolCalls(List.of()).build()
        )));
        when(chatClient.prompt(any(Prompt.class))
                .system(anyString())
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .call()
                .chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()))
                .thenReturn(new ChatClientResponse(finalResponse, Map.of()));

        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger stepNo = new AtomicInteger(1);
        when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + stepNo.getAndIncrement())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());
        when(logService.startStep(anyString(), anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + stepNo.getAndIncrement())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());

        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-1")
                .actualToolName("searchProjectCode")
                .canonicalToolName("searchProjectCode")
                .toolCallLogId("tool-log-1")
                .startedAtMillis(System.currentTimeMillis())
                .build();
        when(toolExecutionService.beforeToolCall(any(), any())).thenReturn(record);

        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenThrow(new IllegalArgumentException("Failed to parse JSON argument: missing required field query"));

        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0));
        SseService sseService = mock(SseService.class);

        JChatMind agent = new JChatMind(
                "agent-1",
                "test-model",
                "test-agent",
                "test",
                "system",
                chatClient,
                20,
                List.of(new UserMessage("hello")),
                List.of(),
                List.of(),
                "session-1",
                sseService,
                toolExecutionService,
                chatMessageFacadeService,
                mock(ChatMessageConverter.class),
                logService,
                compressor,
                "user-message-1",
                List.of("searchProjectCode"),
                new ToolCorrectionProperties(),
                new ToolFailureClassifier()
        );
        ReflectionTestUtils.setField(agent, "toolCallingManager", toolCallingManager);

        agent.run();

        verify(toolExecutionService).afterToolFailure(any(), eq(record), any(IllegalArgumentException.class), eq(true));
        verify(logService, never()).failTask(anyString(), anyString(), anyInt(), anyInt());
        verify(logService).finishTask(eq("task-1"), anyString(), anyInt(), anyInt());
        verify(sseService, never()).sendEvent(eq("session-1"),
                org.mockito.ArgumentMatchers.argThat(event -> event.getType() == AgentSseEvent.Type.ERROR));

        ArgumentCaptor<ChatMessageDTO> messageCaptor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService, atLeastOnce()).createChatMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getAllValues().stream()
                .anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL
                        && message.getContent() != null
                        && message.getContent().contains("Tool call failed")));
    }

    @Test
    void exceedingCorrectionAttemptsFailsTask() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "searchProjectCode",
                                "{\"repoId\":\"repo-1\"}"
                        )))
                        .build()
        )));
        when(chatClient.prompt(any(Prompt.class))
                .system(anyString())
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .call()
                .chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()))
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()));

        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger stepNo = new AtomicInteger(1);
        when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + stepNo.getAndIncrement())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());

        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-1")
                .actualToolName("searchProjectCode")
                .canonicalToolName("searchProjectCode")
                .toolCallLogId("tool-log-1")
                .startedAtMillis(System.currentTimeMillis())
                .build();
        when(toolExecutionService.beforeToolCall(any(), any())).thenReturn(record);

        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenThrow(new IllegalArgumentException("Failed to parse JSON argument: missing required field query"));

        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0));

        ToolCorrectionProperties properties = new ToolCorrectionProperties();
        properties.setMaxAttempts(1);
        JChatMind agent = new JChatMind(
                "agent-1",
                "test-model",
                "test-agent",
                "test",
                "system",
                chatClient,
                20,
                List.of(new UserMessage("hello")),
                List.of(),
                List.of(),
                "session-1",
                mock(SseService.class),
                toolExecutionService,
                chatMessageFacadeService,
                mock(ChatMessageConverter.class),
                logService,
                compressor,
                "user-message-1",
                List.of("searchProjectCode"),
                properties,
                new ToolFailureClassifier()
        );
        ReflectionTestUtils.setField(agent, "toolCallingManager", toolCallingManager);

        assertThrows(RuntimeException.class, agent::run);

        verify(toolExecutionService).afterToolFailure(any(), eq(record), any(IllegalArgumentException.class), eq(true));
        verify(toolExecutionService).afterToolFailure(any(), eq(record), any(IllegalArgumentException.class), eq(false));
        verify(logService).failStepAndTask(anyString(), eq("task-1"), anyString(), anyInt(), anyInt());
    }
}
