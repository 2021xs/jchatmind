package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.config.ToolTimeoutProperties;
import com.kama.jchatmind.config.ToolResultProperties;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolDuplicateCallException;
import com.kama.jchatmind.tool.ToolDuplicateCallState;
import com.kama.jchatmind.tool.ToolArgumentException;
import com.kama.jchatmind.tool.ToolRegistry;
import com.kama.jchatmind.tool.ToolTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private ThreadPoolTaskExecutor toolExecutor;
    private ToolCallBatchExecutor batchExecutor;
    private final ToolExecutionContext executionContext = ToolExecutionContext.builder()
            .taskId("task-1")
            .stepId("step-1")
            .sessionId("session-1")
            .runtimeToolNames(List.of("fastTool", "slowTool", "toolA", "toolB", "toolC"))
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
        batchExecutor = new ToolCallBatchExecutor(
                toolExecutionService, toolExecutor, timeoutProperties,
                new ToolResultGuard(resultProperties),
                new ToolDuplicateCallDetector(new ObjectMapper(), new ToolDuplicateDetectionProperties()),
                toolRegistry);
    }

    @AfterEach
    void tearDown() {
        toolExecutor.shutdown();
    }

    @Test
    void fastToolReturnsOriginalResultAndKeepsSuccessTrace() {
        ToolCallback fastTool = callback("fastTool", ignored -> "original-result");

        ToolCallBatchResult result = execute(List.of(fastTool), call("call-1", "fastTool"));

        assertTrue(result.succeeded());
        assertEquals("original-result",
                result.getToolResponseMessage().getResponses().get(0).responseData());
        verify(toolExecutionService).afterToolSuccess(eq(executionContext), any(), eq("original-result"));
        verify(toolExecutionService, never()).afterToolFailure(eq(executionContext), any(), any(), any(Boolean.class));
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
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(toolExecutionService).afterToolFailure(eq(executionContext), any(), error.capture(), eq(false));
        assertInstanceOf(ToolTimeoutException.class, error.getValue());
        assertTrue(error.getValue().getMessage().contains("50 ms"));
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
    void oversizedSuccessfulResultIsGuardedBeforeTraceAndToolResponse() {
        resultProperties.setDefaultMaxResultChars(100);
        String raw = "x".repeat(180) + "RAW-TAIL";
        ToolCallback tool = callback("fastTool", ignored -> raw);

        ToolCallBatchResult result = execute(List.of(tool), call("call-1", "fastTool"));

        assertTrue(result.succeeded());
        String guarded = result.getToolResponseMessage().getResponses().get(0).responseData();
        assertEquals(100, guarded.codePointCount(0, guarded.length()));
        assertTrue(guarded.contains("[TRUNCATED: originalChars="));
        assertTrue(result.getRecords().get(0).isRuntimeResultTruncated());
        assertEquals(raw.length(), result.getRecords().get(0).getOriginalResultChars());
        assertEquals(100, result.getRecords().get(0).getStoredResultChars());
        verify(toolExecutionService).afterToolSuccess(eq(executionContext), any(), eq(guarded));
    }

    @Test
    void resultOverrideAllowsLargerSuccessfulResult() {
        resultProperties.setDefaultMaxResultChars(80);
        resultProperties.setOverrides(Map.of("fastTool", 160));
        String raw = "x".repeat(140);

        ToolCallBatchResult result = execute(
                List.of(callback("fastTool", ignored -> raw)), call("call-1", "fastTool"));

        assertEquals(raw, result.getToolResponseMessage().getResponses().get(0).responseData());
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
        assertTrue(result.getToolResponseMessage().getResponses().get(1).responseData().contains("[TRUNCATED:"));
        assertEquals("C-ok", result.getToolResponseMessage().getResponses().get(2).responseData());
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
        }

        assertEquals(3, invocations.get());
        verify(toolExecutionService, never()).afterToolFailure(
                eq(executionContext), any(), any(ToolDuplicateCallException.class), eq(false));
    }

    private ToolCallBatchResult execute(List<ToolCallback> callbacks, AssistantMessage.ToolCall... calls) {
        Prompt prompt = Prompt.builder()
                .messages(List.of(new UserMessage("test")))
                .chatOptions(DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .toolCallbacks(callbacks)
                        .build())
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
        return batchExecutor.execute(prompt, response, ToolCallingManager.builder().build(), executionContext);
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
