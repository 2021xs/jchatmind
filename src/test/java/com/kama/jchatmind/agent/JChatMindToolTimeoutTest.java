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
import com.kama.jchatmind.tool.ToolTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindToolTimeoutTest {

    @Test
    void timeoutFailsCurrentStepAndTaskWithoutAnotherThinkRound() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "slowTool", "{}")))
                        .build())));
        when(chatClient.prompt(any(Prompt.class))
                .system(anyString())
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .call()
                .chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()));

        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger stepNo = new AtomicInteger();
        when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + stepNo.incrementAndGet())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());

        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-1")
                .actualToolName("slowTool")
                .canonicalToolName("slowTool")
                .toolCallLogId("tool-log-1")
                .terminalRecorded(true)
                .build();
        ToolTimeoutException timeout = new ToolTimeoutException(
                "Tool 'slowTool' exceeded runtime timeout of 50 ms; interrupt/cancel requested=true, Agent Task will stop",
                null);
        ToolCallBatchExecutor batchExecutor = mock(ToolCallBatchExecutor.class);
        when(batchExecutor.execute(any(), any(), any(), any()))
                .thenReturn(ToolCallBatchResult.builder()
                        .status(ToolCallBatchResult.Status.FAILED)
                        .records(List.of(record))
                        .toolResponseMessage(ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        "call-1", "slowTool", "TOOL_CALL_TERMINAL:\nstatus=ERROR")))
                                .build())
                        .terminalStatuses(Map.of("call-1", ToolCallBatchResult.TerminalStatus.ERROR))
                        .error(timeout)
                        .build());

        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        when(messageService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(
                        false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));
        SseService sseService = mock(SseService.class);

        JChatMind agent = new JChatMind(
                "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                List.of(new UserMessage("hello")), List.of(), List.of(), "session-1", sseService,
                mock(ToolExecutionService.class), messageService, mock(ChatMessageConverter.class),
                logService, compressor, "user-message-1", List.of("slowTool"),
                new ToolCorrectionProperties(), new ToolFailureClassifier(), batchExecutor);

        assertThrows(RuntimeException.class, agent::run);

        verify(batchExecutor, times(1)).execute(any(), any(), any(), any());
        verify(messageService).createToolProtocolBatch(
                eq("session-1"), eq("task-1"), any(AssistantMessage.class), any(ToolResponseMessage.class));
        verify(logService).failStepAndTask(anyString(), eq("task-1"),
                org.mockito.ArgumentMatchers.contains("runtime timeout"), anyInt(), eq(1));
        verify(logService, never()).finishTask(anyString(), anyString(), anyInt(), anyInt());
        verify(sseService).sendEvent(eq("session-1"),
                org.mockito.ArgumentMatchers.argThat(event -> event.getType() == AgentSseEvent.Type.ERROR));
    }
}
