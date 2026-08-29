package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.config.ToolResultProperties;
import com.kama.jchatmind.config.ToolTimeoutProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
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
import com.kama.jchatmind.tool.ToolDuplicateCallException;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindDuplicateToolCallTest {

    @Test
    void agentCanChangeStrategyAfterDuplicateFeedbackAndFinishSuccessfully() {
        AtomicInteger toolAInvocations = new AtomicInteger();
        AtomicInteger toolBInvocations = new AtomicInteger();
        ToolCallback toolA = callback("toolA", toolAInvocations, "A-result");
        ToolCallback toolB = callback("toolB", toolBInvocations, "B-result");
        List<ChatResponse> responses = List.of(
                toolCallResponse("call-a1", "toolA", "{\"query\":\"same\"}"),
                toolCallResponse("call-a2", "toolA", "{\"query\":\"same\"}"),
                toolCallResponse("call-a3", "toolA", "{\"query\":\"same\"}"),
                toolCallResponse("call-b1", "toolB", "{\"query\":\"different\"}"),
                finalResponse("done"));

        try (AgentHarness harness = new AgentHarness(responses, List.of(toolA, toolB))) {
            harness.agent.run();

            assertEquals(2, toolAInvocations.get());
            assertEquals(1, toolBInvocations.get());
            verify(harness.finalCompletionService).complete(any());
            verify(harness.toolExecutionService).afterToolFailure(
                    any(), any(), any(ToolDuplicateCallException.class), eq(false));

            ArgumentCaptor<ToolResponseMessage> persistedResponses =
                    ArgumentCaptor.forClass(ToolResponseMessage.class);
            verify(harness.messageService, atLeastOnce()).createToolProtocolBatch(
                    eq("session-1"), eq("task-1"), any(AssistantMessage.class), persistedResponses.capture());
            assertTrue(persistedResponses.getAllValues().stream()
                    .flatMap(message -> message.getResponses().stream())
                    .anyMatch(response -> response.responseData().contains("reason=DUPLICATE_TOOL_CALL")));
        }
    }

    @Test
    void repeatedCallAfterFeedbackForcesValidatedFinalWithoutAnotherPlanningToolExposure() {
        AtomicInteger toolAInvocations = new AtomicInteger();
        ToolCallback toolA = callback("toolA", toolAInvocations, "A-result");
        List<ChatResponse> responses = List.of(
                toolCallResponse("call-a1", "toolA", "{}"),
                toolCallResponse("call-a2", "toolA", "{}"),
                toolCallResponse("call-a3", "toolA", "{}"),
                toolCallResponse("call-a4", "toolA", "{}"),
                finalResponse("answer from existing evidence"));

        try (AgentHarness harness = new AgentHarness(responses, List.of(toolA))) {
            harness.agent.run();

            assertEquals(2, toolAInvocations.get());
            verify(harness.toolExecutionService, times(2)).afterToolFailure(
                    any(), any(), any(ToolDuplicateCallException.class), eq(false));
            verify(harness.finalCompletionService).complete(any());

            ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
            verify(harness.requestSpec, times(4)).toolCallbacks(callbacks.capture());
            assertTrue(callbacks.getAllValues().stream().allMatch(value -> value.length == 1));

            ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
            verify((ChatClient) org.springframework.test.util.ReflectionTestUtils.getField(
                    harness.agent, "chatClient"), times(5)).prompt(prompts.capture());
            org.springframework.ai.model.tool.ToolCallingChatOptions finalOptions =
                    (org.springframework.ai.model.tool.ToolCallingChatOptions)
                            prompts.getAllValues().get(4).getOptions();
            assertTrue(finalOptions.getToolCallbacks().isEmpty());
            assertTrue(finalOptions.getToolNames().isEmpty());

            verify(harness.requestSpec, times(4)).system(anyString());
        }
    }

    private static ChatResponse toolCallResponse(String id, String name, String arguments) {
        return response(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build());
    }

    private static ChatResponse finalResponse(String content) {
        return response(AssistantMessage.builder().content(content).toolCalls(List.of()).build());
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ToolCallback callback(String name, AtomicInteger invocations, String result) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                invocations.incrementAndGet();
                return result;
            }
        };
    }

    private static final class AgentHarness implements AutoCloseable {
        private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        private final AgentTaskLogService logService = mock(AgentTaskLogService.class);
        private final ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        private final ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        private final ThreadPoolTaskExecutor toolExecutor = new ThreadPoolTaskExecutor();
        private final JChatMind agent;
        private final FinalCompletionService finalCompletionService;

        private AgentHarness(List<ChatResponse> responses, List<ToolCallback> callbacks) {
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
            when(requestSpec.system(anyString())).thenReturn(requestSpec);
            when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(callSpec);
            Queue<ChatResponse> responseQueue = new ArrayDeque<>(responses);
            when(callSpec.chatClientResponse()).thenAnswer(ignored ->
                    new ChatClientResponse(responseQueue.remove(), Map.of()));
            clearInvocations(chatClient, requestSpec, callSpec);

            when(logService.startTask(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyInt(), anyString()))
                    .thenReturn(AgentTask.builder().id("task-1").build());
            AtomicInteger stepSequence = new AtomicInteger();
            when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> AgentStep.builder()
                            .id("step-" + stepSequence.incrementAndGet())
                            .stepNo(invocation.getArgument(1))
                            .stepType(invocation.getArgument(2))
                            .build());

            AtomicInteger recordSequence = new AtomicInteger();
            when(toolExecutionService.beforeToolCall(any(), any()))
                    .thenAnswer(invocation -> {
                        AssistantMessage.ToolCall call = invocation.getArgument(1);
                        return ToolExecutionRecord.builder()
                                .toolCallId(call.id())
                                .actualToolName(call.name())
                                .canonicalToolName(call.name())
                                .toolCallLogId("tool-log-" + recordSequence.incrementAndGet())
                                .startedAtMillis(System.currentTimeMillis())
                                .build();
                    });

            ToolRegistry toolRegistry = mock(ToolRegistry.class);
            when(toolRegistry.canonicalName(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
            toolExecutor.setCorePoolSize(2);
            toolExecutor.setMaxPoolSize(2);
            toolExecutor.setQueueCapacity(4);
            toolExecutor.initialize();

            ToolCallBatchExecutor batchExecutor = new ToolCallBatchExecutor(
                    toolExecutionService,
                    toolExecutor,
                    new ToolTimeoutProperties(),
                    new ToolResultGuard(new ToolResultProperties()),
                    new ToolDuplicateCallDetector(new ObjectMapper(), new ToolDuplicateDetectionProperties()),
                    toolRegistry);
            when(messageService.createChatMessage(any(ChatMessageDTO.class)))
                    .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
            when(messageService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
            ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
            when(compressor.check(anyString(), anyString(), any()))
                    .thenReturn(new ConversationContextCompressor.CompressionCheck(
                            false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

            agent = new JChatMind(
                    "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                    List.of(new UserMessage("question")), callbacks, List.of(), "session-1",
                    mock(SseService.class), toolExecutionService, messageService,
                    mock(ChatMessageConverter.class), logService, compressor, "user-message-1",
                    callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList(),
                    new ToolCorrectionProperties(), new ToolFailureClassifier(), batchExecutor);
            finalCompletionService = JChatMindSafeFinalTestSupport.configure(
                    agent, requestSpec, "validated final answer");
        }

        @Override
        public void close() {
            toolExecutor.shutdown();
        }
    }
}
