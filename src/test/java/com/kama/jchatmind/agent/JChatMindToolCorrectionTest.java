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
import com.kama.jchatmind.service.FinalCompletionService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolArgumentException;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(
                Prompt.builder().messages(List.of(new UserMessage("fixture"))).build());

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

        ToolCallBatchExecutor batchExecutor = mock(ToolCallBatchExecutor.class);
        when(batchExecutor.projectForContext(any(), any(), any())).thenAnswer(invocation ->
                new ToolCallBatchResult.ContextView(invocation.getArgument(2), null));
        when(batchExecutor.execute(any(Prompt.class), any(ChatResponse.class), any(), any()))
                .thenReturn(ToolCallBatchResult.builder()
                        .status(ToolCallBatchResult.Status.FAILED)
                        .records(List.of(record))
                        .toolResponseMessage(errorResponse())
                        .terminalStatuses(Map.of("call-1", ToolCallBatchResult.TerminalStatus.ERROR))
                        .error(new ToolArgumentException(
                                "Failed to parse JSON argument: missing required field query", null))
                        .build());

        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0, 0));
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
                new ToolFailureClassifier(),
                batchExecutor
        );
        FinalCompletionService finalCompletionService = JChatMindSafeFinalTestSupport.configure(
                agent, requestSpec, "validated final answer");

        agent.run();

        verify(batchExecutor).recordFailure(any(), eq(List.of(record)), any(ToolArgumentException.class), eq(true));
        verify(logService, never()).failTask(anyString(), anyString(), anyInt(), anyInt());
        verify(finalCompletionService).complete(any());
        verify(logService, never()).finishTask(anyString(), anyString(), anyInt(), anyInt());
        verify(sseService, never()).sendEvent(eq("session-1"),
                org.mockito.ArgumentMatchers.argThat(event -> event.getType() == AgentSseEvent.Type.ERROR));

        ArgumentCaptor<ToolResponseMessage> responseCaptor = ArgumentCaptor.forClass(ToolResponseMessage.class);
        verify(chatMessageFacadeService).createToolProtocolBatch(
                eq("session-1"), eq("task-1"), any(AssistantMessage.class), responseCaptor.capture());
        assertTrue(responseCaptor.getValue().getResponses().stream()
                .anyMatch(response -> response.responseData().contains("Tool call failed")));
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

        ToolCallBatchExecutor batchExecutor = mock(ToolCallBatchExecutor.class);
        when(batchExecutor.projectForContext(any(), any(), any())).thenAnswer(invocation ->
                new ToolCallBatchResult.ContextView(invocation.getArgument(2), null));
        when(batchExecutor.execute(any(Prompt.class), any(ChatResponse.class), any(), any()))
                .thenReturn(ToolCallBatchResult.builder()
                        .status(ToolCallBatchResult.Status.FAILED)
                        .records(List.of(record))
                        .toolResponseMessage(errorResponse())
                        .terminalStatuses(Map.of("call-1", ToolCallBatchResult.TerminalStatus.ERROR))
                        .error(new ToolArgumentException(
                                "Failed to parse JSON argument: missing required field query", null))
                        .build());

        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        when(chatMessageFacadeService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0, 0));

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
                new ToolFailureClassifier(),
                batchExecutor
        );

        assertThrows(RuntimeException.class, agent::run);

        verify(batchExecutor).recordFailure(any(), eq(List.of(record)), any(ToolArgumentException.class), eq(true));
        verify(batchExecutor).recordFailure(any(), eq(List.of(record)), any(ToolArgumentException.class), eq(false));
        verify(logService).failStepAndTask(anyString(), eq("task-1"), anyString(), anyInt(), anyInt());
    }

    private static ToolResponseMessage errorResponse() {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "searchProjectCode", "TOOL_CALL_TERMINAL:\nstatus=ERROR")))
                .build();
    }
}
