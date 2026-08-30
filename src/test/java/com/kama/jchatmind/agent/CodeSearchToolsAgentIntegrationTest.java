package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.CodeSearchTools;
import com.kama.jchatmind.agent.tools.CodeChunkTools;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeChunkExactReadResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.ConversationContextCompressor;
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
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSearchToolsAgentIntegrationTest {

    @Test
    void compactEvidenceIsGuardedPersistedAndVisibleToNextThink() {
        CodeAnswerEvidenceResult answerEvidence = CodeAnswerEvidenceResult.builder()
                .selectedEvidence(List.of(CodeSearchResult.builder()
                        .chunkId("internal-uuid")
                        .repoId("repo-1")
                        .filePath("src/main/java/example/OrderService.java")
                        .symbolName("OrderService#create")
                        .chunkType("SERVICE_METHOD")
                        .startLine(40)
                        .endLine(58)
                        .score(0.97)
                        .metadata("{\"internal\":true}")
                        .contentPreview("void create() {}")
                        .build()))
                .rawCount(20)
                .selectorLatencyMs(456)
                .jsonParseOk(true)
                .selectorReason("internal reason")
                .build();
        CodeRagAnswerEvidenceService evidenceService = mock(CodeRagAnswerEvidenceService.class);
        when(evidenceService.retrieve("repo-1", "find create flow")).thenReturn(answerEvidence);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.canonicalName("searchProjectCode")).thenReturn("searchProjectCode");
        when(toolRegistry.truncateResult(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        CodeSearchTools codeSearchTools = new CodeSearchTools(evidenceService);
        ToolCallback callback = MethodToolCallbackProvider.builder()
                .toolObjects(codeSearchTools).build().getToolCallbacks()[0];

        ChatResponse toolCallResponse = response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "searchProjectCode",
                        "{\"repoId\":\"repo-1\",\"query\":\"find create flow\"}")))
                .build());
        ChatResponse finalResponse = response(AssistantMessage.builder()
                .content("The create flow is in OrderService#create.").toolCalls(List.of()).build());
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt(any(Prompt.class)).system(anyString())
                .toolCallbacks(any(ToolCallback[].class)).call().chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()))
                .thenReturn(new ChatClientResponse(finalResponse, Map.of()));
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(
                Prompt.builder().messages(List.of(new UserMessage("fixture"))).build());
        clearInvocations(chatClient, requestSpec);

        AgentTaskLogService logService = mockLogService();
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.beforeToolCall(any(), any()))
                .thenReturn(ToolExecutionRecord.builder()
                        .toolCallId("call-1").actualToolName("searchProjectCode")
                        .canonicalToolName("searchProjectCode").toolCallLogId("tool-log-1")
                        .startedAtMillis(System.currentTimeMillis()).build());
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(
                        false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

        try (ToolCallBatchExecutorFixture fixture = new ToolCallBatchExecutorFixture(toolExecutionService, toolRegistry)) {
            JChatMind agent = new JChatMind(
                    "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                    List.of(new UserMessage("find create flow")), List.of(callback), List.of(), "session-1",
                    mock(SseService.class), toolExecutionService, messageService,
                    mock(com.kama.jchatmind.converter.ChatMessageConverter.class), logService, compressor,
                    "user-message-1", List.of("searchProjectCode"), new ToolCorrectionProperties(),
                    new ToolFailureClassifier(), fixture.batchExecutor());
            JChatMindSafeFinalTestSupport.configure(agent, requestSpec, "validated final answer");
            agent.run();
        }

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient, times(3)).prompt(prompts.capture());
        String nextThinkEvidence = toolResult(prompts.getAllValues().get(1).getInstructions());
        assertCompactPresentation(nextThinkEvidence);

        ArgumentCaptor<ToolResponseMessage> stored = ArgumentCaptor.forClass(ToolResponseMessage.class);
        verify(messageService).createToolProtocolBatch(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("task-1"),
                any(AssistantMessage.class), stored.capture());
        assertEquals(nextThinkEvidence, stored.getValue().getResponses().get(0).responseData());
        verify(toolExecutionService).afterToolSuccess(any(), any(ToolExecutionRecord.class),
                org.mockito.ArgumentMatchers.eq(nextThinkEvidence));
    }

    @Test
    void exactChunkRereadReceivesTrustedRuntimeScopeAndUsesNormalAgentProtocol() {
        String repoId = "11111111-1111-1111-1111-111111111111";
        String chunkId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        CodeChunkMapper chunkMapper = mock(CodeChunkMapper.class);
        CodeRepositoryMapper repositoryMapper = mock(CodeRepositoryMapper.class);
        when(repositoryMapper.selectById(repoId)).thenReturn(
                CodeRepository.builder().id(repoId).status("READY").build());
        when(chunkMapper.selectByRepoIdAndChunkId(repoId, chunkId)).thenReturn(
                CodeChunkExactReadResult.builder()
                        .repoId(repoId).chunkId(chunkId).filePath("Exact.java")
                        .symbolName("Exact#read").chunkType("METHOD")
                        .startLine(1).endLine(10).content("MARKER_AGENT_EXACT")
                        .build());
        ToolCallback callback = MethodToolCallbackProvider.builder()
                .toolObjects(new CodeChunkTools(chunkMapper, repositoryMapper))
                .build().getToolCallbacks()[0];
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.canonicalName("getCodeChunk")).thenReturn("getCodeChunk");
        when(toolRegistry.truncateResult(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        ChatResponse toolCallResponse = response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-exact", "function", "getCodeChunk",
                        "{\"repoId\":\"" + repoId + "\",\"chunkId\":\"" + chunkId + "\"}")))
                .build());
        ChatResponse finalResponse = response(AssistantMessage.builder()
                .content("Exact source recovered.").toolCalls(List.of()).build());
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt(any(Prompt.class)).system(anyString())
                .toolCallbacks(any(ToolCallback[].class)).call().chatClientResponse())
                .thenReturn(new ChatClientResponse(toolCallResponse, Map.of()))
                .thenReturn(new ChatClientResponse(finalResponse, Map.of()));
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(
                Prompt.builder().messages(List.of(new UserMessage("fixture"))).build());
        clearInvocations(chatClient, requestSpec);

        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.beforeToolCall(any(), any()))
                .thenReturn(ToolExecutionRecord.builder()
                        .toolCallId("call-exact").actualToolName("getCodeChunk")
                        .canonicalToolName("getCodeChunk").toolCallLogId("tool-log-exact")
                        .startedAtMillis(System.currentTimeMillis()).build());
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
        ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
        when(compressor.check(anyString(), anyString(), any()))
                .thenReturn(new ConversationContextCompressor.CompressionCheck(
                        false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));

        try (ToolCallBatchExecutorFixture fixture = new ToolCallBatchExecutorFixture(
                toolExecutionService, toolRegistry)) {
            JChatMind agent = new JChatMind(
                    "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                    List.of(new UserMessage("reread exact chunk")), List.of(callback), List.of(), "session-1",
                    mock(SseService.class), toolExecutionService, messageService,
                    mock(com.kama.jchatmind.converter.ChatMessageConverter.class), mockLogService(), compressor,
                    "user-message-1", List.of("getCodeChunk"), new ToolCorrectionProperties(),
                    new ToolFailureClassifier(), fixture.batchExecutor());
            agent.setTrustedRepoId(repoId);
            JChatMindSafeFinalTestSupport.configure(agent, requestSpec, "validated final answer");
            agent.run();
        }

        ArgumentCaptor<ToolResponseMessage> stored = ArgumentCaptor.forClass(ToolResponseMessage.class);
        verify(messageService).createToolProtocolBatch(
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("task-1"),
                any(AssistantMessage.class), stored.capture());
        String persistent = stored.getValue().getResponses().get(0).responseData();
        assertTrue(persistent.contains("MARKER_AGENT_EXACT"));
        assertTrue(persistent.contains("repoId: " + repoId));
        verify(chunkMapper).selectByRepoIdAndChunkId(repoId, chunkId);
    }

    private AgentTaskLogService mockLogService() {
        AgentTaskLogService service = mock(AgentTaskLogService.class);
        when(service.startTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(AgentTask.builder().id("task-1").build());
        AtomicInteger steps = new AtomicInteger();
        when(service.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AgentStep.builder().id("step-" + steps.incrementAndGet())
                        .stepNo(invocation.getArgument(1)).stepType(invocation.getArgument(2)).build());
        return service;
    }

    private String toolResult(List<Message> messages) {
        return messages.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).findFirst().orElseThrow()
                .getResponses().get(0).responseData();
    }

    private void assertCompactPresentation(String result) {
        assertTrue(result.contains("Selected code evidence:"));
        assertTrue(result.contains("repoId: repo-1"));
        assertTrue(result.contains("chunkId: internal-uuid"));
        assertTrue(result.contains("file: src/main/java/example/OrderService.java"));
        assertTrue(result.contains("symbol: OrderService#create"));
        assertTrue(result.contains("snippet:"));
        assertTrue(result.contains("void create() {}"));
        assertTrue(result.contains("Code evidence novelty:"));
        assertTrue(result.contains("returnedEvidenceCount=1"));
        assertTrue(result.contains("newEvidenceCount=1"));
        assertFalse(result.contains("selectorLatencyMs"));
        assertFalse(result.contains("metadata:"));
        assertFalse(result.contains("score:"));
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
