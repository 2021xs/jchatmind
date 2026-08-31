package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.CodeSearchTools;
import com.kama.jchatmind.agent.tools.CodeChunkTools;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.config.ToolTimeoutProperties;
import com.kama.jchatmind.config.ToolResultProperties;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.mcp.McpToolCallException;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeChunkExactReadResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.service.impl.EstimatedTokenCounter;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolDuplicateCallException;
import com.kama.jchatmind.tool.ToolDuplicateCallState;
import com.kama.jchatmind.tool.ToolArgumentException;
import com.kama.jchatmind.tool.ToolRegistry;
import com.kama.jchatmind.tool.ToolTimeoutException;
import com.kama.jchatmind.tool.ToolUnknownException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallBatchExecutorTest {
    private ToolExecutionService toolExecutionService;
    private ToolRegistry toolRegistry;
    private ToolTimeoutProperties timeoutProperties;
    private ToolResultProperties resultProperties;
    private ContextCompressionProperties compressionProperties;
    private ThreadPoolTaskExecutor toolExecutor;
    private ToolCallBatchExecutor batchExecutor;
    private ToolCallBatchResult.ContextView lastContextView;
    private final ToolExecutionContext executionContext = ToolExecutionContext.builder()
            .taskId("task-1")
            .stepId("step-1")
            .sessionId("session-1")
            .runtimeToolNames(List.of("fastTool", "slowTool", "toolA", "toolB", "toolC",
                    "searchProjectCode", "getCodeChunk"))
            .duplicateCallState(new ToolDuplicateCallState())
            .build();

    @BeforeEach
    void setUp() {
        toolExecutionService = mock(ToolExecutionService.class);
        toolRegistry = mock(ToolRegistry.class);
        timeoutProperties = new ToolTimeoutProperties();
        timeoutProperties.setDefaultTimeout(Duration.ofMillis(500));
        resultProperties = new ToolResultProperties();
        resultProperties.setDefaultMaxResultChars(8000);
        when(toolRegistry.canonicalName(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicInteger logId = new AtomicInteger();
        when(toolExecutionService.beforeToolCall(eq(executionContext), any()))
                .thenAnswer(invocation -> {
                    AssistantMessage.ToolCall call = invocation.getArgument(1);
                    return ToolExecutionRecord.builder()
                            .toolCallId(call.id())
                            .actualToolName(call.name())
                            .canonicalToolName(call.name())
                            .toolCallLogId("log-" + logId.incrementAndGet())
                            .startedAtMillis(System.currentTimeMillis())
                            .build();
                });
        toolExecutor = new ThreadPoolTaskExecutor();
        toolExecutor.setCorePoolSize(2);
        toolExecutor.setMaxPoolSize(2);
        toolExecutor.setQueueCapacity(4);
        toolExecutor.setThreadNamePrefix("tool-timeout-test-");
        toolExecutor.initialize();
        compressionProperties = new ContextCompressionProperties();
        batchExecutor = new ToolCallBatchExecutor(
                toolExecutionService, toolExecutor, timeoutProperties,
                new ToolResultGuard(resultProperties),
                new ToolDuplicateCallDetector(new ObjectMapper(), new ToolDuplicateDetectionProperties()),
                toolRegistry, new EstimatedTokenCounter(compressionProperties), compressionProperties);
    }

    @AfterEach
    void tearDown() {
        toolExecutor.shutdown();
    }

    @Test
    void fastToolReturnsOriginalResultAndKeepsSuccessTrace() {
        ToolCallback fastTool = callback("fastTool", ignored -> "original-result");

        AtomicReference<AgentLifecycleObservationPublisher.ToolResultObservation> observed =
                new AtomicReference<>();
        ToolCallBatchResult result;
        try (AgentLifecycleObservationPublisher.Registration ignored =
                     AgentLifecycleObservationPublisher.registerToolResult(observed::set)) {
            result = execute(List.of(fastTool), call("call-1", "fastTool"));
        }

        assertTrue(result.succeeded());
        assertEquals("original-result",
                result.getToolResponseMessage().getResponses().get(0).responseData());
        assertEquals("original-result",
                lastContextView.toolResponseMessage().getResponses().get(0).responseData());
        verify(toolExecutionService).afterToolSuccess(eq(executionContext), any(), eq("original-result"));
        verify(toolExecutionService, never()).afterToolFailure(eq(executionContext), any(), any(), any(Boolean.class));
        assertEquals("task-1", observed.get().taskId());
        assertEquals("original-result", observed.get().rawResult());
        assertEquals("original-result", observed.get().contextResult());
        assertEquals("SUCCESS", observed.get().status());
    }

    @Test
    void allSuccessfulCallsProduceExactlyOneMatchingResponseEach() {
        ToolCallBatchResult result = execute(
                List.of(callback("toolA", ignored -> "A-ok"), callback("toolB", ignored -> "B-ok")),
                call("call-a", "toolA"), call("call-b", "toolB"));

        assertTrue(result.succeeded());
        assertCompleteIds(result, Set.of("call-a", "call-b"));
        assertEquals(List.of("A-ok", "B-ok"), result.getToolResponseMessage().getResponses().stream()
                .map(response -> response.responseData())
                .toList());
    }

    @Test
    void partialSuccessPreservesEarlierExactResultAndAddsFailureResponse() {
        ToolCallBatchResult result = execute(
                List.of(callback("toolA", ignored -> "A-complete-result"),
                        callback("toolB", ignored -> {
                            throw new ToolArgumentException("invalid toolB argument", null);
                        })),
                call("call-a", "toolA"), call("call-b", "toolB"));

        assertEquals(ToolCallBatchResult.Status.FAILED, result.getStatus());
        assertCompleteIds(result, Set.of("call-a", "call-b"));
        assertEquals("A-complete-result",
                result.getToolResponseMessage().getResponses().get(0).responseData());
        assertEquals(ToolCallBatchResult.TerminalStatus.SUCCESS,
                result.getTerminalStatuses().get("call-a"));
        assertEquals(ToolCallBatchResult.TerminalStatus.ERROR,
                result.getTerminalStatuses().get("call-b"));
    }

    @Test
    void mcpInvocationFailureRemainsFailureThroughUnifiedRuntime() {
        ToolCallback mcpTool = callback("mcp_docs_search_docs", ignored -> {
            throw new McpToolCallException("mcp_docs_search_docs",
                    new IllegalStateException("credential=secret-token command=/private/path"));
        });

        ToolCallBatchResult result = execute(List.of(mcpTool),
                call("call-1", "mcp_docs_search_docs"));

        assertEquals(ToolCallBatchResult.Status.FAILED, result.getStatus());
        assertInstanceOf(McpToolCallException.class, result.getError());
        assertEquals("MCP_TOOL_CALL_FAILED", ((McpToolCallException) result.getError()).getErrorType());
        verify(toolExecutionService, never()).afterToolSuccess(eq(executionContext), any(), any());

        batchExecutor.recordFailure(executionContext, result.getRecords(), result.getError(), false);

        verify(toolExecutionService).afterToolFailure(eq(executionContext), any(ToolExecutionRecord.class),
                any(McpToolCallException.class), eq(false));
    }

    @Test
    void timeoutCancelsFutureAndRecordsToolTimeout() throws InterruptedException {
        timeoutProperties.setDefaultTimeout(Duration.ofMillis(50));
        CountDownLatch interrupted = new CountDownLatch(1);
        ToolCallback slowTool = callback("slowTool", ignored -> {
            try {
                Thread.sleep(10_000);
                return "late-result";
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });

        ToolCallBatchResult result = execute(List.of(slowTool), call("call-1", "slowTool"));

        assertEquals(ToolCallBatchResult.Status.FAILED, result.getStatus());
        assertInstanceOf(ToolTimeoutException.class, result.getError());
        assertCompleteIds(result, Set.of("call-1"));
        assertEquals(ToolCallBatchResult.TerminalStatus.ERROR,
                result.getTerminalStatuses().get("call-1"));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(toolExecutionService).afterToolFailure(eq(executionContext), any(), error.capture(), eq(false));
        assertInstanceOf(ToolTimeoutException.class, error.getValue());
        assertTrue(error.getValue().getMessage().contains("50 ms"));
    }

    @Test
    void userCancellationCancelsToolFutureWithoutRecordingTimeout() throws InterruptedException {
        AgentTaskControl control = new AgentTaskControl("task-1", "session-1");
        ToolExecutionContext context = ToolExecutionContext.builder()
                .taskId("task-1")
                .stepId("step-1")
                .sessionId("session-1")
                .runtimeToolNames(List.of("slowTool"))
                .duplicateCallState(new ToolDuplicateCallState())
                .cancellationControl(control)
                .build();
        CountDownLatch started = new CountDownLatch(1);
        ToolCallback slowTool = callback("slowTool", ignored -> {
            started.countDown();
            sleep(10_000);
            return "late";
        });
        when(toolExecutionService.beforeToolCall(eq(context), any()))
                .thenReturn(ToolExecutionRecord.builder()
                        .toolCallId("call-1")
                        .actualToolName("slowTool")
                        .canonicalToolName("slowTool")
                        .toolCallLogId("log-cancel")
                        .startedAtMillis(System.currentTimeMillis())
                        .build());

        java.util.concurrent.FutureTask<ToolCallBatchResult> run = new java.util.concurrent.FutureTask<>(
                () -> execute(context, List.of(slowTool), call("call-1", "slowTool")));
        Thread thread = new Thread(run);
        thread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        control.requestCancellation();
        thread.join(5_000);

        assertTrue(run.isDone());
        verify(toolExecutionService, never()).afterToolFailure(eq(context), any(), any(ToolTimeoutException.class), eq(false));
    }

    @Test
    void perToolOverrideCanExtendGlobalTimeout() {
        timeoutProperties.setDefaultTimeout(Duration.ofMillis(20));
        timeoutProperties.setOverrides(Map.of("slowTool", Duration.ofMillis(500)));
        ToolCallback slowTool = callback("slowTool", ignored -> {
            sleep(75);
            return "completed-with-override";
        });

        ToolCallBatchResult result = execute(List.of(slowTool), call("call-1", "slowTool"));

        assertTrue(result.succeeded());
        assertEquals("completed-with-override",
                result.getToolResponseMessage().getResponses().get(0).responseData());
        verify(toolExecutionService).afterToolSuccess(eq(executionContext), any(), eq("completed-with-override"));
    }

    @Test
    void oversizedSuccessfulResultKeepsPersistentBodyAndBoundsContextView() {
        resultProperties.setDefaultMaxResultChars(100);
        String raw = "x".repeat(180) + "RAW-TAIL";
        ToolCallback tool = callback("fastTool", ignored -> raw);

        AtomicReference<AgentLifecycleObservationPublisher.ToolResultObservation> observed =
                new AtomicReference<>();
        ToolCallBatchResult result;
        try (AgentLifecycleObservationPublisher.Registration ignored =
                     AgentLifecycleObservationPublisher.registerToolResult(observed::set)) {
            result = execute(List.of(tool), call("call-1", "fastTool"));
        }

        assertTrue(result.succeeded());
        assertEquals(raw, result.getToolResponseMessage().getResponses().get(0).responseData());
        String guarded = lastContextView.toolResponseMessage().getResponses().get(0).responseData();
        String reloaded = batchExecutor.projectPersistedResponseForContext(
                result.getToolResponseMessage().getResponses().get(0)).responseData();
        assertEquals(100, guarded.codePointCount(0, guarded.length()));
        assertEquals(guarded, reloaded);
        assertTrue(guarded.contains("[TRUNCATED: originalChars="));
        assertTrue(result.getRecords().get(0).isRuntimeResultTruncated());
        assertEquals(raw.length(), result.getRecords().get(0).getOriginalResultChars());
        assertEquals(100, result.getRecords().get(0).getStoredResultChars());
        assertEquals(raw, observed.get().rawResult());
        assertEquals(guarded, observed.get().contextResult());
        verify(toolExecutionService).afterToolSuccess(eq(executionContext), any(), eq(guarded));
    }

    @Test
    void smallPersistentReloadUsesExactSameContextProjectionPolicy() {
        String canonical = "PARTIAL\nrowsReturned=50\nhasMore=true";
        ToolCallBatchResult result = execute(
                List.of(callback("fastTool", ignored -> canonical)), call("call-small", "fastTool"));

        String live = lastContextView.toolResponseMessage().getResponses().get(0).responseData();
        String reloaded = batchExecutor.projectPersistedResponseForContext(
                result.getToolResponseMessage().getResponses().get(0)).responseData();

        assertEquals(canonical, live);
        assertEquals(canonical, reloaded);
    }

    @Test
    void persistentReloadProjectionFailureKeepsTerminalResponseFailClosed() {
        resultProperties.setDefaultMaxResultChars(10);
        ToolResponseMessage.ToolResponse persistent =
                new ToolResponseMessage.ToolResponse("call-failed-projection", "fastTool", "x".repeat(200));

        ToolResponseMessage.ToolResponse projected =
                batchExecutor.projectPersistedResponseForContext(persistent);

        assertEquals(persistent.id(), projected.id());
        assertEquals(persistent.name(), projected.name());
        assertEquals("TOOL_RESULT_CONTEXT_UNAVAILABLE: persisted; unsafe to inject.", projected.responseData());
    }

    @Test
    void persistentReloadProjectionIsToolAgnosticAndPreservesProtocolIdentity() {
        List<String> toolNames = List.of(
                "searchProjectCode", "getCodeChunk", "databaseQuery", "knowledgeQuery", "mcp.snapshot");
        for (int index = 0; index < toolNames.size(); index++) {
            String toolName = toolNames.get(index);
            String body = index == 2 ? "PARTIAL\nrowsReturned=50\nhasMore=true" : "canonical-" + toolName;
            ToolResponseMessage.ToolResponse persistent =
                    new ToolResponseMessage.ToolResponse("call-" + index, toolName, body);

            ToolResponseMessage.ToolResponse projected =
                    batchExecutor.projectPersistedResponseForContext(persistent);

            assertEquals(persistent.id(), projected.id());
            assertEquals(persistent.name(), projected.name());
            assertEquals(body, projected.responseData());
        }
    }

    @Test
    void oversizedCodeResultKeepsFullFormattedCanonicalAndBoundsContextView() {
        String tailMarker = "CODE-CANONICAL-TAIL";
        CodeRagAnswerEvidenceService evidenceService = mock(CodeRagAnswerEvidenceService.class);
        when(evidenceService.retrieve("repo-1", "large query")).thenReturn(CodeAnswerEvidenceResult.builder()
                .selectedEvidence(List.of(CodeSearchResult.builder()
                        .repoId("repo-1")
                        .chunkId("chunk-large")
                        .filePath("LargeService.java")
                        .symbolName("LargeService#run")
                        .chunkType("SERVICE_METHOD")
                        .startLine(1)
                        .endLine(500)
                        .contentPreview("x".repeat(12_000) + tailMarker)
                        .build()))
                .build());
        String canonical = new CodeSearchTools(evidenceService)
                .searchProjectCode("repo-1", "large query");

        ToolCallBatchResult result = execute(
                List.of(callback("searchProjectCode", ignored -> canonical)),
                call("call-code", "searchProjectCode", "{\"repoId\":\"repo-1\",\"query\":\"large query\"}"));

        String persistent = result.getToolResponseMessage().getResponses().get(0).responseData();
        String context = lastContextView.toolResponseMessage().getResponses().get(0).responseData();
        assertTrue(persistent.length() > 7_000);
        assertEquals(canonical, persistent);
        assertTrue(persistent.endsWith(tailMarker + "\n"));
        assertEquals(8_000, context.length());
        assertTrue(context.contains("repoId: repo-1"));
        assertTrue(context.contains("chunkId: chunk-large"));
        assertTrue(context.contains("[TRUNCATED: originalChars="));
        assertFalse(context.contains(tailMarker));
    }

    @Test
    void exactCodeRereadUsesNormalPersistentResultAndBoundedContextLifecycle() {
        String repoId = "11111111-1111-1111-1111-111111111111";
        String chunkId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String tailMarker = "EXACT-CHUNK-TAIL";
        CodeChunkMapper chunkMapper = mock(CodeChunkMapper.class);
        CodeRepositoryMapper repositoryMapper = mock(CodeRepositoryMapper.class);
        when(repositoryMapper.selectById(repoId)).thenReturn(
                CodeRepository.builder().id(repoId).status("READY").build());
        when(chunkMapper.selectByRepoIdAndChunkId(repoId, chunkId)).thenReturn(
                CodeChunkExactReadResult.builder()
                        .repoId(repoId).chunkId(chunkId).filePath("Exact.java")
                        .symbolName("Exact#read").chunkType("METHOD")
                        .startLine(1).endLine(300)
                        .content("x".repeat(12_000) + tailMarker)
                        .build());
        ToolCallback exactRead = MethodToolCallbackProvider.builder()
                .toolObjects(new CodeChunkTools(chunkMapper, repositoryMapper))
                .build().getToolCallbacks()[0];

        ToolCallBatchResult result = execute(executionContext, List.of(exactRead),
                Map.of(CodeChunkTools.TRUSTED_REPO_ID_TOOL_CONTEXT_KEY, repoId),
                call("call-exact", "getCodeChunk",
                        "{\"repoId\":\"" + repoId + "\",\"chunkId\":\"" + chunkId + "\"}"));

        String persistent = result.getToolResponseMessage().getResponses().get(0).responseData();
        String context = lastContextView.toolResponseMessage().getResponses().get(0).responseData();
        assertTrue(result.succeeded());
        assertTrue(persistent.contains("repoId: " + repoId));
        assertTrue(persistent.contains("chunkId: " + chunkId));
        assertTrue(persistent.contains(tailMarker));
        assertEquals(12_210, persistent.length());
        assertEquals(8_000, context.length());
        assertTrue(context.contains("[TRUNCATED: originalChars="));
        assertFalse(context.contains(tailMarker));
        verify(chunkMapper).selectByRepoIdAndChunkId(repoId, chunkId);
        verify(toolExecutionService).afterToolSuccess(eq(executionContext), any(), eq(context));
    }

    @Test
    void oversizedPartialSuccessKeepsCanonicalSuccessAndCompletesFailedBatch() {
        resultProperties.setDefaultMaxResultChars(100);
        String canonicalA = "A-" + "x".repeat(180) + "-RAW-TAIL";
        AtomicInteger toolCInvocations = new AtomicInteger();

        ToolCallBatchResult result = execute(
                List.of(callback("toolA", ignored -> canonicalA),
                        callback("toolB", ignored -> {
                            throw new ToolArgumentException("toolB failed", null);
                        }),
                        callback("toolC", ignored -> {
                            toolCInvocations.incrementAndGet();
                            return "C-ok";
                        })),
                call("call-a", "toolA"), call("call-b", "toolB"), call("call-c", "toolC"));

        assertEquals(ToolCallBatchResult.Status.FAILED, result.getStatus());
        assertCompleteIds(result, Set.of("call-a", "call-b", "call-c"));
        assertEquals(canonicalA, result.getToolResponseMessage().getResponses().get(0).responseData());
        String contextA = lastContextView.toolResponseMessage().getResponses().get(0).responseData();
        assertEquals(100, contextA.codePointCount(0, contextA.length()));
        assertTrue(contextA.contains("[TRUNCATED: originalChars="));
        assertEquals(ToolCallBatchResult.TerminalStatus.SUCCESS,
                result.getTerminalStatuses().get("call-a"));
        assertEquals(ToolCallBatchResult.TerminalStatus.ERROR,
                result.getTerminalStatuses().get("call-b"));
        assertEquals(ToolCallBatchResult.TerminalStatus.SKIPPED,
                result.getTerminalStatuses().get("call-c"));
        assertEquals(0, toolCInvocations.get());
    }

    @Test
    void projectionFailureKeepsCanonicalResponseAndFailsClosedForModel() {
        resultProperties.setDefaultMaxResultChars(1);
        String canonical = "canonical-" + "x".repeat(200);

        ToolCallBatchResult result = execute(
                List.of(callback("fastTool", ignored -> canonical)), call("call-1", "fastTool"));

        assertEquals(canonical, result.getToolResponseMessage().getResponses().get(0).responseData());
        assertEquals("TOOL_RESULT_CONTEXT_UNAVAILABLE: persisted; unsafe to inject.",
                lastContextView.toolResponseMessage().getResponses().get(0).responseData());
        assertTrue(result.getRecords().get(0).isRuntimeResultTruncated());
    }

    @Test
    void resultOverrideAllowsLargerSuccessfulResult() {
        resultProperties.setDefaultMaxResultChars(80);
        resultProperties.setOverrides(Map.of("fastTool", 160));
        String raw = "x".repeat(140);

        ToolCallBatchResult result = execute(
                List.of(callback("fastTool", ignored -> raw)), call("call-1", "fastTool"));

        assertEquals(raw, result.getToolResponseMessage().getResponses().get(0).responseData());
        assertEquals(raw, lastContextView.toolResponseMessage().getResponses().get(0).responseData());
        assertTrue(!result.getRecords().get(0).isRuntimeResultTruncated());
        assertEquals(160, result.getRecords().get(0).getMaxResultChars());
    }

    @Test
    void multiToolTruncationDoesNotStopLaterTools() {
        resultProperties.setDefaultMaxResultChars(100);
        AtomicInteger toolCInvocations = new AtomicInteger();
        ToolCallback toolA = callback("toolA", ignored -> "A-ok");
        ToolCallback toolB = callback("toolB", ignored -> "b".repeat(200));
        ToolCallback toolC = callback("toolC", ignored -> {
            toolCInvocations.incrementAndGet();
            return "C-ok";
        });

        ToolCallBatchResult result = execute(
                List.of(toolA, toolB, toolC),
                call("call-a", "toolA"), call("call-b", "toolB"), call("call-c", "toolC"));

        assertTrue(result.succeeded());
        assertEquals(3, result.getToolResponseMessage().getResponses().size());
        assertEquals("A-ok", result.getToolResponseMessage().getResponses().get(0).responseData());
        assertEquals("b".repeat(200), result.getToolResponseMessage().getResponses().get(1).responseData());
        assertTrue(lastContextView.toolResponseMessage().getResponses().get(1).responseData()
                .contains("[TRUNCATED:"));
        assertEquals("C-ok", result.getToolResponseMessage().getResponses().get(2).responseData());
        assertCompleteIds(result, Set.of("call-a", "call-b", "call-c"));
        assertEquals(ToolCallBatchResult.TerminalStatus.SUCCESS,
                result.getTerminalStatuses().get("call-a"));
        assertEquals(ToolCallBatchResult.TerminalStatus.SUCCESS,
                result.getTerminalStatuses().get("call-b"));
        assertEquals(ToolCallBatchResult.TerminalStatus.SUCCESS,
                result.getTerminalStatuses().get("call-c"));
        assertEquals(1, toolCInvocations.get());
        assertTrue(result.getRecords().get(1).isRuntimeResultTruncated());
    }

    @Test
    void multiToolStopsAfterTimeoutAndPreservesEarlierSuccess() {
        timeoutProperties.setDefaultTimeout(Duration.ofMillis(50));
        AtomicInteger toolCInvocations = new AtomicInteger();
        ToolCallback toolA = callback("toolA", ignored -> "A-ok");
        ToolCallback toolB = callback("toolB", ignored -> {
            sleep(10_000);
            return "B-late";
        });
        ToolCallback toolC = callback("toolC", ignored -> {
            toolCInvocations.incrementAndGet();
            return "C-ok";
        });

        ToolCallBatchResult result = execute(
                List.of(toolA, toolB, toolC),
                call("call-a", "toolA"),
                call("call-b", "toolB"),
                call("call-c", "toolC"));

        assertEquals(ToolCallBatchResult.Status.FAILED, result.getStatus());
        assertEquals(2, result.getRecords().size());
        assertEquals(0, toolCInvocations.get());
        assertCompleteIds(result, Set.of("call-a", "call-b", "call-c"));
        assertEquals("A-ok", result.getToolResponseMessage().getResponses().get(0).responseData());
        assertEquals(ToolCallBatchResult.TerminalStatus.SUCCESS,
                result.getTerminalStatuses().get("call-a"));
        assertEquals(ToolCallBatchResult.TerminalStatus.ERROR,
                result.getTerminalStatuses().get("call-b"));
        assertEquals(ToolCallBatchResult.TerminalStatus.SKIPPED,
                result.getTerminalStatuses().get("call-c"));
        assertTrue(result.getToolResponseMessage().getResponses().get(1).responseData().contains("status=ERROR"));
        assertTrue(result.getToolResponseMessage().getResponses().get(2).responseData().contains("status=SKIPPED"));
        verify(toolExecutionService).afterToolSuccess(
                eq(executionContext), eq(result.getRecords().get(0)), eq("A-ok"));
        verify(toolExecutionService).afterToolFailure(
                eq(executionContext), eq(result.getRecords().get(1)), any(ToolTimeoutException.class), eq(false));
        verify(toolExecutionService, never()).beforeToolCall(
                eq(executionContext), org.mockito.ArgumentMatchers.argThat(call -> "toolC".equals(call.name())));

        batchExecutor.recordFailure(executionContext, result.getRecords(), result.getError(), false);
        verify(toolExecutionService, times(1)).afterToolSuccess(
                eq(executionContext), eq(result.getRecords().get(0)), eq("A-ok"));
        verify(toolExecutionService, times(1)).afterToolFailure(
                eq(executionContext), eq(result.getRecords().get(1)), any(ToolTimeoutException.class), eq(false));
    }

    @Test
    void sameBatchThirdIdenticalCallIsRejectedWithoutInvokingCallback() {
        AtomicInteger invocations = new AtomicInteger();
        ToolCallback tool = callback("toolA", ignored -> {
            invocations.incrementAndGet();
            return "A-ok";
        });

        ToolCallBatchResult result = execute(
                List.of(tool),
                call("call-1", "toolA", "{\"x\":1,\"y\":2}"),
                call("call-2", "toolA", "{\"y\":2,\"x\":1}"),
                call("call-3", "toolA", "{\"x\":1,\"y\":2}"));

        assertTrue(result.succeeded());
        assertEquals(2, invocations.get());
        assertEquals(3, result.getRecords().size());
        String feedback = result.getToolResponseMessage().getResponses().get(2).responseData();
        assertTrue(feedback.contains("reason=DUPLICATE_TOOL_CALL"));
        assertTrue(feedback.contains("consecutiveCount=3"));
        assertCompleteIds(result, Set.of("call-1", "call-2", "call-3"));
        assertEquals(ToolCallBatchResult.TerminalStatus.REJECTED,
                result.getTerminalStatuses().get("call-3"));
        verify(toolExecutionService, times(2)).afterToolSuccess(eq(executionContext), any(), eq("A-ok"));
        verify(toolExecutionService).afterToolFailure(
                eq(executionContext), eq(result.getRecords().get(2)),
                any(ToolDuplicateCallException.class), eq(false));
    }

    @Test
    void sameToolDifferentArgsAndNonConsecutiveCallsAllExecute() {
        AtomicInteger toolAInvocations = new AtomicInteger();
        AtomicInteger toolBInvocations = new AtomicInteger();
        ToolCallback toolA = callback("toolA", ignored -> {
            toolAInvocations.incrementAndGet();
            return "A-ok";
        });
        ToolCallback toolB = callback("toolB", ignored -> {
            toolBInvocations.incrementAndGet();
            return "B-ok";
        });

        ToolCallBatchResult result = execute(
                List.of(toolA, toolB),
                call("call-1", "toolA", "{\"query\":\"A\"}"),
                call("call-2", "toolA", "{\"query\":\"B\"}"),
                call("call-3", "toolB", "{\"query\":\"B\"}"),
                call("call-4", "toolA", "{\"query\":\"B\"}"));

        assertTrue(result.succeeded());
        assertEquals(3, toolAInvocations.get());
        assertEquals(1, toolBInvocations.get());
        verify(toolExecutionService, never()).afterToolFailure(
                eq(executionContext), any(), any(ToolDuplicateCallException.class), eq(false));
    }

    @Test
    void correctableArgumentFailureDoesNotTurnIntoDuplicateRejection() {
        AtomicInteger invocations = new AtomicInteger();
        ToolCallback invalidTool = callback("toolA", ignored -> {
            invocations.incrementAndGet();
            throw new ToolArgumentException("missing required field query", null);
        });

        for (int i = 1; i <= 3; i++) {
            ToolCallBatchResult result = execute(
                    List.of(invalidTool), call("call-" + i, "toolA", "{\"repoId\":\"repo-1\"}"));
            assertEquals(ToolCallBatchResult.Status.FAILED, result.getStatus());
            assertInstanceOf(ToolArgumentException.class, result.getError());
            assertCompleteIds(result, Set.of("call-" + i));
            assertEquals(ToolCallBatchResult.TerminalStatus.ERROR,
                    result.getTerminalStatuses().get("call-" + i));
        }

        assertEquals(3, invocations.get());
        verify(toolExecutionService, never()).afterToolFailure(
                eq(executionContext), any(), any(ToolDuplicateCallException.class), eq(false));
    }

    @Test
    void noNoveltyGuardReturnsProtocolSafeFeedbackWithoutCallingCodeSearch() {
        TaskEvidenceState evidenceState = blockedEvidenceState();
        ToolExecutionContext guardedContext = ToolExecutionContext.builder()
                .taskId("task-guard")
                .stepId("step-guard")
                .sessionId("session-1")
                .runtimeToolNames(List.of("searchProjectCode"))
                .duplicateCallState(new ToolDuplicateCallState())
                .taskEvidenceState(evidenceState)
                .build();
        when(toolExecutionService.beforeToolCall(eq(guardedContext), any()))
                .thenReturn(ToolExecutionRecord.builder()
                        .toolCallId("call-3")
                        .actualToolName("searchProjectCode")
                        .canonicalToolName("searchProjectCode")
                        .toolCallLogId("log-guard")
                        .startedAtMillis(System.currentTimeMillis())
                        .build());
        AtomicInteger retrievalInvocations = new AtomicInteger();
        ToolCallback codeSearch = callback("searchProjectCode", ignored -> {
            retrievalInvocations.incrementAndGet();
            return "should-not-run";
        });

        ToolCallBatchResult result = execute(guardedContext, List.of(codeSearch),
                call("call-3", "searchProjectCode", "{\"repoId\":\"repo-1\",\"query\":\"rewrite\"}"));

        assertTrue(result.succeeded());
        assertEquals(0, retrievalInvocations.get());
        assertEquals(1, result.getToolResponseMessage().getResponses().size());
        assertTrue(result.getToolResponseMessage().getResponses().get(0).responseData()
                .contains("CODE_SEARCH_NO_NOVELTY_GUARD"));
        assertEquals(1, evidenceState.snapshot().guardedSearchRequestCount());
        verify(toolExecutionService).afterToolSuccess(eq(guardedContext), any(),
                org.mockito.ArgumentMatchers.contains("CODE_SEARCH_NO_NOVELTY_GUARD"));
        verify(toolExecutionService, never()).afterToolFailure(eq(guardedContext), any(), any(), any(Boolean.class));
    }

    @Test
    void unknownToolProducesOneRejectedTerminalResponse() {
        when(toolExecutionService.beforeToolCall(eq(executionContext), any()))
                .thenThrow(new ToolUnknownException("Unknown tool: missingTool"));

        ToolCallBatchResult result = execute(List.of(), call("call-unknown", "missingTool"));

        assertEquals(ToolCallBatchResult.Status.FAILED, result.getStatus());
        assertCompleteIds(result, Set.of("call-unknown"));
        assertEquals(ToolCallBatchResult.TerminalStatus.REJECTED,
                result.getTerminalStatuses().get("call-unknown"));
        assertTrue(result.getToolResponseMessage().getResponses().get(0).responseData()
                .contains("status=REJECTED"));
    }

    @Test
    void codeSearchGuardDoesNotBlockOtherTools() {
        TaskEvidenceState evidenceState = blockedEvidenceState();
        ToolExecutionContext guardedContext = ToolExecutionContext.builder()
                .taskId("task-other-tool")
                .stepId("step-other-tool")
                .sessionId("session-1")
                .runtimeToolNames(List.of("databaseQuery"))
                .duplicateCallState(new ToolDuplicateCallState())
                .taskEvidenceState(evidenceState)
                .build();
        when(toolExecutionService.beforeToolCall(eq(guardedContext), any()))
                .thenReturn(ToolExecutionRecord.builder()
                        .toolCallId("call-db")
                        .actualToolName("databaseQuery")
                        .canonicalToolName("databaseQuery")
                        .toolCallLogId("log-db")
                        .startedAtMillis(System.currentTimeMillis())
                        .build());
        AtomicInteger invocations = new AtomicInteger();

        ToolCallBatchResult result = execute(guardedContext,
                List.of(callback("databaseQuery", ignored -> {
                    invocations.incrementAndGet();
                    return "db-result";
                })), call("call-db", "databaseQuery", "{\"sql\":\"SELECT 1\"}"));

        assertTrue(result.succeeded());
        assertEquals(1, invocations.get());
        assertEquals("db-result", result.getToolResponseMessage().getResponses().get(0).responseData());
    }

    private TaskEvidenceState blockedEvidenceState() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "empty-1", List.of());
        state.observeSearch("repo-1", "empty-2", List.of());
        return state;
    }

    private ToolCallBatchResult execute(List<ToolCallback> callbacks, AssistantMessage.ToolCall... calls) {
        return execute(executionContext, callbacks, calls);
    }

    private ToolCallBatchResult execute(ToolExecutionContext context,
                                        List<ToolCallback> callbacks,
                                        AssistantMessage.ToolCall... calls) {
        return execute(context, callbacks, Map.of(), calls);
    }

    private ToolCallBatchResult execute(ToolExecutionContext context,
                                        List<ToolCallback> callbacks,
                                        Map<String, Object> toolContext,
                                        AssistantMessage.ToolCall... calls) {
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("test")))
                .chatOptions(DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .toolCallbacks(callbacks)
                        .toolContext(toolContext)
                        .build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
        ToolCallBatchResult result = batchExecutor.execute(
                prompt, response, ToolCallingManager.builder().build(), context);
        lastContextView = batchExecutor.projectForContext(context, result, result.getToolResponseMessage());
        return result;
    }

    private AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private ToolCallback callback(String name, Function<String, String> function) {
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
                return function.apply(toolInput);
            }
        };
    }

    private void assertCompleteIds(ToolCallBatchResult result, Set<String> requestedIds) {
        Set<String> responseIds = result.getToolResponseMessage().getResponses().stream()
                .map(response -> response.id())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(requestedIds, responseIds);
        assertEquals(requestedIds.size(), result.getToolResponseMessage().getResponses().size());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
