package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindFinalizationTest {

    @Test
    void terminateRunsToolDisabledFinalizationAndPersistsAnswerBeforeSuccess() {
        ClientHarness clientHarness = mockChatClient(List.of(
                toolCallResponse("call-1", "terminate", "{}"),
                answerResponse("answer from evidence")));
        ChatClient chatClient = clientHarness.client;
        List<String> events = new ArrayList<>();
        AgentTaskLogService logService = mockLogService(events);
        ChatMessageFacadeService messageService = mockMessageService(events);
        ToolCallBatchExecutor batchExecutor = mockBatchExecutor();
        when(batchExecutor.execute(any(), any(), any(), any())).thenReturn(terminateResult());

        JChatMind agent = newAgent(chatClient, logService, messageService, batchExecutor,
                List.of(callback("terminate")));
        agent.run();

        ArgumentCaptor<org.springframework.ai.tool.ToolCallback[]> callbacks =
                ArgumentCaptor.forClass(org.springframework.ai.tool.ToolCallback[].class);
        verify(chatClient, times(2)).prompt(any(Prompt.class));
        verify(clientHarness.requestSpec, times(2)).toolCallbacks(callbacks.capture());
        assertTrue(callbacks.getAllValues().get(1).length == 0);

        ArgumentCaptor<ChatMessageDTO> messages = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(messageService, atLeastOnce()).createChatMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().anyMatch(message ->
                message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                        && "answer from evidence".equals(message.getContent())));

        verify(logService).finishTask(eq("task-1"), eq(AgentTaskLogService.FINISH_REASON_TERMINATE_TOOL),
                anyInt(), eq(1));
        int finalAnswerIndex = events.indexOf("message:ASSISTANT:answer from evidence");
        int successIndex = events.indexOf("finishTask");
        assertTrue(finalAnswerIndex >= 0 && finalAnswerIndex < successIndex,
                "Assistant answer must be persisted before task success: " + events);
    }

    @Test
    void emptyTerminateFinalizationFailsWithoutSuccessOrAnswer() {
        ClientHarness clientHarness = mockChatClient(List.of(
                toolCallResponse("call-1", "terminate", "{}"),
                answerResponse("   ")));
        ChatClient chatClient = clientHarness.client;
        AgentTaskLogService logService = mockLogService(new ArrayList<>());
        ChatMessageFacadeService messageService = mockMessageService(new ArrayList<>());
        ToolCallBatchExecutor batchExecutor = mockBatchExecutor();
        when(batchExecutor.execute(any(), any(), any(), any())).thenReturn(terminateResult());

        JChatMind agent = newAgent(chatClient, logService, messageService, batchExecutor,
                List.of(callback("terminate")));
        assertThrows(RuntimeException.class, agent::run);

        verify(logService).failStepAndTask(anyString(), eq("task-1"),
                org.mockito.ArgumentMatchers.contains("empty final answer"), anyInt(), eq(1));
        verify(logService, never()).finishTask(anyString(), anyString(), anyInt(), anyInt());
        verify(messageService, times(2)).createChatMessage(any(ChatMessageDTO.class));
    }

    @Test
    void terminateFinalizationToolCallFailsWithoutExecutingAnotherBatch() {
        ClientHarness clientHarness = mockChatClient(List.of(
                toolCallResponse("call-1", "terminate", "{}"),
                toolCallResponse("call-2", "terminate", "{}")));
        ChatClient chatClient = clientHarness.client;
        AgentTaskLogService logService = mockLogService(new ArrayList<>());
        ChatMessageFacadeService messageService = mockMessageService(new ArrayList<>());
        ToolCallBatchExecutor batchExecutor = mockBatchExecutor();
        when(batchExecutor.execute(any(), any(), any(), any())).thenReturn(terminateResult());

        JChatMind agent = newAgent(chatClient, logService, messageService, batchExecutor,
                List.of(callback("terminate")));
        assertThrows(RuntimeException.class, agent::run);

        verify(batchExecutor, times(1)).execute(any(), any(), any(), any());
        verify(logService).failStepAndTask(anyString(), eq("task-1"),
                org.mockito.ArgumentMatchers.contains("another tool"), anyInt(), eq(1));
    }

    @Test
    void maxStepFinalRoundRemainsToolDisabledWithoutExtraFinalization() {
        ClientHarness clientHarness = mockChatClient(List.of(answerResponse("answer at step limit")));
        AgentTaskLogService logService = mockLogService(new ArrayList<>());
        ChatMessageFacadeService messageService = mockMessageService(new ArrayList<>());
        ToolCallBatchExecutor batchExecutor = mockBatchExecutor();
        JChatMind agent = newAgent(clientHarness.client, logService, messageService, batchExecutor,
                List.of(callback("terminate")));
        agent.setMaxLoopSteps(1);

        agent.run();

        ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(clientHarness.requestSpec).toolCallbacks(callbacks.capture());
        assertTrue(callbacks.getValue().length == 0);
        verify(clientHarness.client, times(1)).prompt(any(Prompt.class));
        verify(batchExecutor, never()).execute(any(), any(), any(), any());
        verify(logService).finishTask(eq("task-1"), eq(AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS),
                anyInt(), eq(0));
    }

    private JChatMind newAgent(ChatClient chatClient,
                               AgentTaskLogService logService,
                               ChatMessageFacadeService messageService,
                               ToolCallBatchExecutor batchExecutor,
                               List<ToolCallback> callbacks) {
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(
                        false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));
        return new JChatMind(
                "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                List.of(new UserMessage("question")), callbacks, List.of(), "session-1",
                mock(SseService.class), mock(ToolExecutionService.class), messageService,
                mock(ChatMessageConverter.class), logService, compressor, "user-message-1",
                callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList(),
                new ToolCorrectionProperties(), new ToolFailureClassifier(), batchExecutor);
    }

    private AgentTaskLogService mockLogService(List<String> events) {
        AgentTaskLogService service = mock(AgentTaskLogService.class);
        when(service.startTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger step = new AtomicInteger();
        when(service.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + step.incrementAndGet())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());
        org.mockito.Mockito.doAnswer(invocation -> {
            events.add("finishTask");
            return null;
        }).when(service).finishTask(anyString(), anyString(), anyInt(), anyInt());
        return service;
    }

    private ChatMessageFacadeService mockMessageService(List<String> events) {
        ChatMessageFacadeService service = mock(ChatMessageFacadeService.class);
        AtomicInteger message = new AtomicInteger();
        when(service.createChatMessage(any(ChatMessageDTO.class)))
                .thenAnswer(invocation -> {
                    ChatMessageDTO dto = invocation.getArgument(0);
                    events.add("message:" + dto.getRole() + ":" + dto.getContent());
                    return CreateChatMessageResponse.builder()
                            .chatMessageId("message-" + message.incrementAndGet()).build();
                });
        when(service.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        return service;
    }

    private ToolCallBatchExecutor mockBatchExecutor() {
        return mock(ToolCallBatchExecutor.class);
    }

    private ToolCallBatchResult terminateResult() {
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "terminate", "terminated")))
                .build();
        List<Message> history = List.of(
                new UserMessage("question"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "terminate", "{}")))
                        .build(),
                response);
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.SUCCESS)
                .records(List.of(ToolExecutionRecord.builder()
                        .toolCallId("call-1").actualToolName("terminate").canonicalToolName("terminate").build()))
                .toolResponseMessage(response)
                .toolExecutionResult(org.springframework.ai.model.tool.ToolExecutionResult.builder()
                        .conversationHistory(history).returnDirect(false).build())
                .build();
    }

    private ClientHarness mockChatClient(List<ChatResponse> responses) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        Queue<ChatResponse> queue = new ArrayDeque<>(responses);
        when(callSpec.chatClientResponse())
                .thenAnswer(ignored -> new ChatClientResponse(queue.remove(), Map.of()));
        return new ClientHarness(client, requestSpec);
    }

    private record ClientHarness(ChatClient client, ChatClient.ChatClientRequestSpec requestSpec) {
    }

    private ChatResponse toolCallResponse(String id, String name, String args) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build())));
    }

    private ChatResponse answerResponse(String content) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(content).toolCalls(List.of()).build())));
    }

    private ToolCallback callback(String name) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name).description(name).inputSchema("{\"type\":\"object\"}").build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "terminated";
            }
        };
    }
}
