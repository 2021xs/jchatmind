package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.config.ToolResultProperties;
import com.kama.jchatmind.config.ToolTimeoutProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.RagSearchResult;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindToolResultGuardTest {

    @Test
    void oversizedDatabaseCanonicalResultIsPersistedBeforeBoundedModelViewIsBuilt() {
        String rawResult = """
                Query result:
                status: PARTIAL
                completeness: PARTIAL
                rowsReturned: 50
                rowLimit: 50
                hasMore: true

                rows:
                """ + String.join("\n", IntStream.rangeClosed(1, 50)
                .mapToObj(index -> "%d | %s".formatted(index, "x".repeat(100)))
                .toList()) + "\nDB-CANONICAL-TAIL";

        assertToolResultLifecycle("databaseQuery", rawResult, "DB-CANONICAL-TAIL", 100, true);
    }

    @Test
    void oversizedKnowledgeCanonicalResultIsPersistedBeforeBoundedModelViewIsBuilt() {
        String tailMarker = "_KNOWLEDGE_CANONICAL_TAIL";
        RagService ragService = mock(RagService.class);
        when(ragService.similaritySearchWithMetadata("kb-1", "large query"))
                .thenReturn(List.of(RagSearchResult.builder()
                        .chunkId("chunk-large")
                        .title("Large Knowledge")
                        .sourceType("document_chunk")
                        .sourceId("document-1")
                        .score(0.91)
                        .metadata("{\"category\":\"large\"}")
                        .content("x".repeat(7_000) + tailMarker)
                        .build()));
        String canonical = new KnowledgeTools(ragService).knowledgeQuery("kb-1", "large query");

        assertTrue(canonical.length() > 6_000);
        assertToolResultLifecycle("knowledgeQuery", canonical, tailMarker, 100, true);
    }

    @Test
    void normalKnowledgeCanonicalResultRemainsExactInPersistentAndModelViews() {
        RagService ragService = mock(RagService.class);
        when(ragService.similaritySearchWithMetadata("kb-1", "small query"))
                .thenReturn(List.of(RagSearchResult.builder()
                        .chunkId("chunk-small")
                        .content("KNOWLEDGE-SMALL-TAIL")
                        .build()));
        String canonical = new KnowledgeTools(ragService).knowledgeQuery("kb-1", "small query");

        assertToolResultLifecycle(
                "knowledgeQuery", canonical, "KNOWLEDGE-SMALL-TAIL", 8_000, false);
    }

    private void assertToolResultLifecycle(String toolName, String rawResult, String tailMarker,
                                           int maxResultChars, boolean expectBoundedView) {
        ToolCallback largeTool = callback(toolName, rawResult);

        ChatResponse toolCallResponse = response(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", toolName, "{}")))
                .build());
        ChatResponse finalResponse = response(AssistantMessage.builder()
                .content("done")
                .toolCalls(List.of())
                .build());
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt(any(Prompt.class))
                .system(anyString())
                .toolCallbacks(any(ToolCallback[].class))
                .call()
                .chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()))
                .thenReturn(new ChatClientResponse(finalResponse, Map.of()));
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(
                Prompt.builder().messages(List.of(new UserMessage("fixture"))).build());
        clearInvocations(chatClient, requestSpec);

        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        when(logService.startTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger stepSequence = new AtomicInteger();
        when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder()
                        .id("step-" + stepSequence.incrementAndGet())
                        .stepNo(invocation.getArgument(1))
                        .stepType(invocation.getArgument(2))
                        .build());

        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.beforeToolCall(any(), any()))
                .thenReturn(ToolExecutionRecord.builder()
                        .toolCallId("call-1")
                        .actualToolName(toolName)
                        .canonicalToolName(toolName)
                        .toolCallLogId("tool-log-1")
                        .startedAtMillis(System.currentTimeMillis())
                        .build());
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.canonicalName(toolName)).thenReturn(toolName);

        ToolResultProperties resultProperties = new ToolResultProperties();
        resultProperties.setDefaultMaxResultChars(maxResultChars);
        ThreadPoolTaskExecutor toolExecutor = new ThreadPoolTaskExecutor();
        toolExecutor.setCorePoolSize(1);
        toolExecutor.setMaxPoolSize(1);
        toolExecutor.setQueueCapacity(1);
        toolExecutor.initialize();

        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        when(messageService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(
                        false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

        AtomicReference<AgentLifecycleObservationPublisher.FinalProjectionObservation> finalProjection =
                new AtomicReference<>();
        ToolResultGuard resultGuard = spy(new ToolResultGuard(resultProperties));
        try (AgentLifecycleObservationPublisher.Registration ignored =
                     AgentLifecycleObservationPublisher.registerFinalProjection(finalProjection::set)) {
            ToolCallBatchExecutor batchExecutor = new ToolCallBatchExecutor(
                    toolExecutionService,
                    toolExecutor,
                    new ToolTimeoutProperties(),
                    resultGuard,
                    new ToolDuplicateCallDetector(new ObjectMapper(), new ToolDuplicateDetectionProperties()),
                    toolRegistry);
            JChatMind agent = new JChatMind(
                    "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                    List.of(new UserMessage("find evidence")), List.of(largeTool), List.of(), "session-1",
                    mock(SseService.class), toolExecutionService, messageService,
                    mock(ChatMessageConverter.class), logService, compressor, "user-message-1",
                    List.of(toolName), new ToolCorrectionProperties(), new ToolFailureClassifier(), batchExecutor);
            JChatMindSafeFinalTestSupport.configure(agent, requestSpec, "validated final answer");

            agent.run();
        } finally {
            toolExecutor.shutdown();
        }

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient, times(3)).prompt(prompts.capture());
        List<Message> secondThinkMessages = prompts.getAllValues().get(1).getInstructions();
        ToolResponseMessage nextThinkToolResponse = secondThinkMessages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow();
        String promptResult = nextThinkToolResponse.getResponses().get(0).responseData();

        ArgumentCaptor<AssistantMessage> persistedAssistant = ArgumentCaptor.forClass(AssistantMessage.class);
        ArgumentCaptor<ToolResponseMessage> persistedResponse = ArgumentCaptor.forClass(ToolResponseMessage.class);
        verify(messageService).createToolProtocolBatch(
                eq("session-1"), eq("task-1"), persistedAssistant.capture(), persistedResponse.capture());
        String storedToolResult = persistedResponse.getValue().getResponses().get(0).responseData();

        if (expectBoundedView) {
            assertEquals(maxResultChars, promptResult.codePointCount(0, promptResult.length()));
            assertTrue(promptResult.contains("[TRUNCATED: originalChars="));
            assertFalse(promptResult.contains(tailMarker));
            assertNotEquals(rawResult, promptResult);
        } else {
            assertEquals(rawResult, promptResult);
            assertTrue(promptResult.contains(tailMarker));
            assertFalse(promptResult.contains("[TRUNCATED: originalChars="));
        }
        assertEquals(rawResult, storedToolResult);
        assertEquals(promptResult, finalProjection.get().executionTranscript().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow()
                .getResponses().get(0).responseData());
        String transcriptResult = finalProjection.get().currentTaskToolTranscript().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow()
                .getResponses().get(0).responseData();
        assertEquals(promptResult, transcriptResult);
        assertEquals(0, ProtocolAwareMessageWindowChatMemory.inspectProtocol(List.of(
                persistedAssistant.getValue(), persistedResponse.getValue())).protocolValidationFailureCount());
        verify(toolExecutionService).afterToolSuccess(any(), any(ToolExecutionRecord.class), eq(promptResult));
        org.mockito.InOrder persistenceBeforeProjection = inOrder(
                messageService, resultGuard, toolExecutionService);
        persistenceBeforeProjection.verify(messageService).createToolProtocolBatch(
                eq("session-1"), eq("task-1"), any(AssistantMessage.class), any(ToolResponseMessage.class));
        persistenceBeforeProjection.verify(resultGuard).guard(toolName, toolName, rawResult);
        persistenceBeforeProjection.verify(toolExecutionService)
                .afterToolSuccess(any(), any(ToolExecutionRecord.class), eq(promptResult));
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ToolCallback callback(String name, String result) {
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
                return result;
            }
        };
    }
}
