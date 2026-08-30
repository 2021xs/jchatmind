package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.config.ToolTimeoutProperties;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolArgumentException;
import com.kama.jchatmind.tool.ToolDuplicateCallException;
import com.kama.jchatmind.tool.ToolDuplicateCallState;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionException;
import com.kama.jchatmind.tool.ToolFailureException;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolPolicyRejectedException;
import com.kama.jchatmind.tool.ToolRegistry;
import com.kama.jchatmind.tool.ToolTimeoutException;
import com.kama.jchatmind.tool.ToolUnknownException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class ToolCallBatchExecutor {
    private static final String UNKNOWN_TOOL_SCHEMA = "{\"type\":\"object\"}";
    private static final String CONTEXT_PROJECTION_FAILURE =
            "TOOL_RESULT_CONTEXT_UNAVAILABLE: persisted; unsafe to inject.";

    private final ToolExecutionService toolExecutionService;
    private final AsyncTaskExecutor toolExecutor;
    private final ToolTimeoutProperties timeoutProperties;
    private final ToolResultGuard toolResultGuard;
    private final ToolDuplicateCallDetector duplicateCallDetector;
    private final ToolRegistry toolRegistry;

    public ToolCallBatchExecutor(ToolExecutionService toolExecutionService,
                                 @Qualifier("toolExecutor") AsyncTaskExecutor toolExecutor,
                                 ToolTimeoutProperties timeoutProperties,
                                 ToolResultGuard toolResultGuard,
                                 ToolDuplicateCallDetector duplicateCallDetector,
                                 ToolRegistry toolRegistry) {
        this.toolExecutionService = toolExecutionService;
        this.toolExecutor = toolExecutor;
        this.timeoutProperties = timeoutProperties;
        this.toolResultGuard = toolResultGuard;
        this.duplicateCallDetector = duplicateCallDetector;
        this.toolRegistry = toolRegistry;
    }

    public ToolCallBatchResult execute(Prompt prompt,
                                       ChatResponse chatResponse,
                                       ToolCallingManager toolCallingManager,
                                       ToolExecutionContext executionContext) {
        List<AssistantMessage.ToolCall> requestedCalls = List.copyOf(requestedToolCalls(chatResponse));
        validateRequestedCalls(requestedCalls);
        List<ToolExecutionRecord> records = new ArrayList<>();
        Map<String, TerminalResponse> terminalResponses = new LinkedHashMap<>();
        Prompt executionPrompt = withTimeoutCallbacks(
                prompt, requestedCalls, executionContext, records, terminalResponses);

        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(executionPrompt, chatResponse);
        } catch (IllegalArgumentException e) {
            return failed(requestedCalls, records, terminalResponses,
                    new ToolArgumentException(e.getMessage(), e));
        } catch (RuntimeException e) {
            return failed(requestedCalls, records, terminalResponses, e);
        } catch (Exception e) {
            return failed(requestedCalls, records, terminalResponses,
                    new ToolExecutionException(e.getMessage(), e));
        }

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);
        CompleteTerminalBatch completeBatch;
        try {
            completeBatch = completeSuccessfulBatch(requestedCalls, toolResponseMessage, terminalResponses);
        } catch (RuntimeException e) {
            return failed(requestedCalls, records, terminalResponses, e);
        }
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.SUCCESS)
                .records(records)
                .toolExecutionResult(toolExecutionResult)
                .toolResponseMessage(completeBatch.message())
                .terminalStatuses(completeBatch.statuses())
                .build();
    }

    /**
     * Builds the short-lived model view after the caller has atomically persisted
     * {@code persistentResponseMessage}. The persistent response is never mutated.
     */
    ToolCallBatchResult.ContextView projectForContext(
            ToolExecutionContext executionContext,
            ToolCallBatchResult batchResult,
            ToolResponseMessage persistentResponseMessage) {
        if (batchResult == null || persistentResponseMessage == null) {
            throw new IllegalArgumentException("Batch result and persistent response are required");
        }

        Map<String, ToolExecutionRecord> recordsByCallId = recordsByCallId(batchResult.getRecords());
        List<ToolResponseMessage.ToolResponse> contextResponses = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse persistentResponse : persistentResponseMessage.getResponses()) {
            ToolExecutionRecord record = recordsByCallId.get(persistentResponse.id());
            ToolResultGuard.GuardedToolResult guarded = contextProjection(record, persistentResponse);
            applyResultMetrics(record, guarded);
            publishProjectedToolResult(executionContext, batchResult, record, persistentResponse, guarded);
            contextResponses.add(new ToolResponseMessage.ToolResponse(
                    persistentResponse.id(), persistentResponse.name(), guarded.value()));
        }

        ToolResponseMessage contextResponseMessage = ToolResponseMessage.builder()
                .responses(contextResponses)
                .metadata(persistentResponseMessage.getMetadata())
                .build();
        recordUnfinishedToolResponses(executionContext, batchResult.getRecords(),
                batchResult.getTerminalStatuses(), contextResponseMessage);
        return new ToolCallBatchResult.ContextView(
                contextResponseMessage,
                replaceLastToolResponse(batchResult.getToolExecutionResult(), contextResponseMessage));
    }

    ToolResponseMessage.ToolResponse projectPersistedResponseForContext(
            ToolResponseMessage.ToolResponse persistentResponse) {
        if (persistentResponse == null) {
            throw new IllegalArgumentException("Persistent tool response is required");
        }
        ToolResultGuard.GuardedToolResult guarded = contextProjection(null, persistentResponse);
        return new ToolResponseMessage.ToolResponse(
                persistentResponse.id(), persistentResponse.name(), guarded.value());
    }

    private ToolResultGuard.GuardedToolResult contextProjection(
            ToolExecutionRecord record,
            ToolResponseMessage.ToolResponse persistentResponse) {
        String actualToolName = record == null ? persistentResponse.name() : record.getActualToolName();
        String canonicalToolName = record == null
                ? toolRegistry.canonicalName(persistentResponse.name())
                : record.getCanonicalToolName();
        try {
            return toolResultGuard.guard(
                    actualToolName, canonicalToolName, persistentResponse.responseData());
        } catch (RuntimeException error) {
            int originalChars = codePointCount(persistentResponse.responseData());
            int fallbackChars = codePointCount(CONTEXT_PROJECTION_FAILURE);
            log.warn("Tool result context projection failed closed: toolCallId={}, toolName={}, error={}",
                    persistentResponse.id(), canonicalToolName, error.getMessage());
            return new ToolResultGuard.GuardedToolResult(
                    CONTEXT_PROJECTION_FAILURE, originalChars, fallbackChars, 0, true);
        }
    }

    private ToolExecutionResult replaceLastToolResponse(
            ToolExecutionResult result,
            ToolResponseMessage contextResponseMessage) {
        if (result == null) {
            return null;
        }
        List<Message> history = new ArrayList<>(result.conversationHistory());
        int lastIndex = history.size() - 1;
        if (lastIndex < 0 || !(history.get(lastIndex) instanceof ToolResponseMessage)) {
            throw new IllegalStateException("Tool execution result has no terminal ToolResponseMessage");
        }
        history.set(lastIndex, contextResponseMessage);
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(result.returnDirect())
                .build();
    }

    private Map<String, ToolExecutionRecord> recordsByCallId(List<ToolExecutionRecord> records) {
        Map<String, ToolExecutionRecord> indexed = new LinkedHashMap<>();
        if (records == null) {
            return indexed;
        }
        for (ToolExecutionRecord record : records) {
            if (record != null && record.getToolCallId() != null) {
                indexed.put(record.getToolCallId(), record);
            }
        }
        return indexed;
    }

    private ToolCallBatchResult failed(List<AssistantMessage.ToolCall> requestedCalls,
                                       List<ToolExecutionRecord> records,
                                       Map<String, TerminalResponse> terminalResponses,
                                       RuntimeException error) {
        CompleteTerminalBatch completeBatch = completeFailedBatch(requestedCalls, terminalResponses, error);
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.FAILED)
                .records(records)
                .toolResponseMessage(completeBatch.message())
                .terminalStatuses(completeBatch.statuses())
                .error(error)
                .build();
    }

    public void recordFailure(ToolExecutionContext executionContext,
                              List<ToolExecutionRecord> records,
                              Throwable error,
                              boolean correctionRequested) {
        for (ToolExecutionRecord record : records) {
            if (record.isTerminalRecorded()) {
                continue;
            }
            toolExecutionService.afterToolFailure(
                    executionContext,
                    record,
                    error,
                    correctionRequested
            );
            record.setTerminalRecorded(true);
        }
    }

    private Prompt withTimeoutCallbacks(Prompt prompt,
                                        List<AssistantMessage.ToolCall> requestedCalls,
                                        ToolExecutionContext executionContext,
                                        List<ToolExecutionRecord> records,
                                        Map<String, TerminalResponse> terminalResponses) {
        List<ToolCallback> availableCallbacks = callbacksFrom(prompt);
        Map<String, Object> toolContext = toolContextFrom(prompt);
        List<ToolCallback> timeoutCallbacks = timeoutCallbacks(
                requestedCalls, availableCallbacks, executionContext, records, terminalResponses);
        ToolCallingChatOptions options = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(timeoutCallbacks)
                .toolContext(toolContext)
                .build();
        return Prompt.builder()
                .messages(prompt.getInstructions())
                .chatOptions(options)
                .build();
    }

    private List<ToolCallback> callbacksFrom(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            return options.getToolCallbacks();
        }
        return List.of();
    }

    private Map<String, Object> toolContextFrom(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null) {
            return options.getToolContext();
        }
        return Map.of();
    }

    private List<AssistantMessage.ToolCall> requestedToolCalls(ChatResponse chatResponse) {
        return chatResponse.getResult().getOutput().getToolCalls();
    }

    private void validateRequestedCalls(List<AssistantMessage.ToolCall> requestedCalls) {
        if (requestedCalls.isEmpty()) {
            throw new IllegalArgumentException("Tool batch must contain at least one requested call");
        }
        Set<String> ids = new HashSet<>();
        for (AssistantMessage.ToolCall call : requestedCalls) {
            if (call == null || call.id() == null || call.id().isBlank()) {
                throw new IllegalArgumentException("Every requested tool call must have a non-blank id");
            }
            if (!ids.add(call.id())) {
                throw new IllegalArgumentException("Duplicate requested toolCallId: " + call.id());
            }
        }
    }

    private CompleteTerminalBatch completeSuccessfulBatch(
            List<AssistantMessage.ToolCall> requestedCalls,
            ToolResponseMessage responseMessage,
            Map<String, TerminalResponse> observedResponses) {
        Map<String, ToolResponseMessage.ToolResponse> responsesById = new LinkedHashMap<>();
        for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new ToolExecutionException("Tool response is missing toolCallId", null);
            }
            if (responsesById.putIfAbsent(response.id(), response) != null) {
                throw new ToolExecutionException("Duplicate terminal response for toolCallId " + response.id(), null);
            }
        }

        List<ToolResponseMessage.ToolResponse> ordered = new ArrayList<>();
        Map<String, ToolCallBatchResult.TerminalStatus> statuses = new LinkedHashMap<>();
        for (AssistantMessage.ToolCall call : requestedCalls) {
            ToolResponseMessage.ToolResponse response = responsesById.remove(call.id());
            if (response == null) {
                throw new ToolExecutionException("Missing terminal response for toolCallId " + call.id(), null);
            }
            ordered.add(response);
            TerminalResponse observed = observedResponses.get(call.id());
            statuses.put(call.id(), observed == null
                    ? ToolCallBatchResult.TerminalStatus.SUCCESS
                    : observed.status());
        }
        if (!responsesById.isEmpty()) {
            throw new ToolExecutionException(
                    "Unexpected terminal response toolCallIds: " + responsesById.keySet(), null);
        }
        return completeBatch(ordered, statuses, responseMessage.getMetadata());
    }

    private CompleteTerminalBatch completeFailedBatch(
            List<AssistantMessage.ToolCall> requestedCalls,
            Map<String, TerminalResponse> observedResponses,
            RuntimeException error) {
        List<ToolResponseMessage.ToolResponse> ordered = new ArrayList<>();
        Map<String, ToolCallBatchResult.TerminalStatus> statuses = new LinkedHashMap<>();
        boolean failingCallAssigned = observedResponses.values().stream()
                .anyMatch(response -> response.status() == ToolCallBatchResult.TerminalStatus.ERROR
                        || response.status() == ToolCallBatchResult.TerminalStatus.REJECTED);
        for (AssistantMessage.ToolCall call : requestedCalls) {
            TerminalResponse terminal = observedResponses.get(call.id());
            if (terminal == null) {
                if (!failingCallAssigned) {
                    terminal = failureResponse(call, error);
                    failingCallAssigned = true;
                } else {
                    terminal = skippedResponse(call);
                }
            }
            ordered.add(terminal.response());
            statuses.put(call.id(), terminal.status());
        }
        return completeBatch(ordered, statuses, Map.of());
    }

    private CompleteTerminalBatch completeBatch(
            List<ToolResponseMessage.ToolResponse> responses,
            Map<String, ToolCallBatchResult.TerminalStatus> statuses,
            Map<String, Object> metadata) {
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(responses)
                .metadata(metadata)
                .build();
        return new CompleteTerminalBatch(message, Map.copyOf(statuses));
    }

    private TerminalResponse failureResponse(AssistantMessage.ToolCall call, RuntimeException error) {
        ToolCallBatchResult.TerminalStatus status = error instanceof ToolUnknownException
                || error instanceof ToolPolicyRejectedException
                || error instanceof ToolDuplicateCallException
                ? ToolCallBatchResult.TerminalStatus.REJECTED
                : ToolCallBatchResult.TerminalStatus.ERROR;
        String errorType = error instanceof ToolFailureException failure
                ? failure.getErrorType()
                : error.getClass().getSimpleName();
        String message = error instanceof ToolFailureException failure
                ? failure.getSafeMessage()
                : error.getMessage();
        String payload = "TOOL_CALL_TERMINAL:\n"
                + "status=" + status + "\n"
                + "toolName=" + call.name() + "\n"
                + "errorType=" + errorType + "\n"
                + "message=" + safeTerminalMessage(message);
        return new TerminalResponse(status,
                new ToolResponseMessage.ToolResponse(call.id(), call.name(), payload));
    }

    private TerminalResponse skippedResponse(AssistantMessage.ToolCall call) {
        String payload = "TOOL_CALL_TERMINAL:\n"
                + "status=SKIPPED\n"
                + "reason=BATCH_ABORTED\n"
                + "toolName=" + call.name() + "\n"
                + "message=Not executed because an earlier tool call in the same batch failed.";
        return new TerminalResponse(ToolCallBatchResult.TerminalStatus.SKIPPED,
                new ToolResponseMessage.ToolResponse(call.id(), call.name(), payload));
    }

    private String safeTerminalMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Tool execution failed before a result was returned.";
        }
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 500
                ? singleLine
                : singleLine.substring(0, 484) + "...[truncated]";
    }

    private List<ToolCallback> timeoutCallbacks(List<AssistantMessage.ToolCall> toolCalls,
                                                List<ToolCallback> availableCallbacks,
                                                ToolExecutionContext executionContext,
                                                List<ToolExecutionRecord> records,
                                                Map<String, TerminalResponse> terminalResponses) {
        Map<String, Deque<AssistantMessage.ToolCall>> callsByActualName = new LinkedHashMap<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            callsByActualName.computeIfAbsent(toolCall.name(), ignored -> new ArrayDeque<>()).add(toolCall);
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        for (Map.Entry<String, Deque<AssistantMessage.ToolCall>> entry : callsByActualName.entrySet()) {
            String actualToolName = entry.getKey();
            ToolCallback delegate = findDelegate(actualToolName, availableCallbacks);
            callbacks.add(new RuntimeTimeoutToolCallback(
                    actualToolName, entry.getValue(), delegate, executionContext, records, terminalResponses));
        }
        return callbacks;
    }

    private ToolCallback findDelegate(String actualToolName, List<ToolCallback> availableCallbacks) {
        String canonicalToolName = toolRegistry.canonicalName(actualToolName);
        return availableCallbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().equals(actualToolName)
                        || callback.getToolDefinition().name().equals(canonicalToolName))
                .findFirst()
                .orElse(null);
    }

    private void recordUnfinishedToolResponses(ToolExecutionContext executionContext,
                                               List<ToolExecutionRecord> records,
                                               Map<String, ToolCallBatchResult.TerminalStatus> terminalStatuses,
                                               ToolResponseMessage toolResponseMessage) {
        Map<String, ToolResponseMessage.ToolResponse> responsesById = new LinkedHashMap<>();
        for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
            responsesById.put(response.id(), response);
        }
        for (ToolExecutionRecord record : records) {
            if (record.isTerminalRecorded()) {
                continue;
            }
            ToolCallBatchResult.TerminalStatus status = terminalStatuses == null
                    ? null : terminalStatuses.get(record.getToolCallId());
            if (status != ToolCallBatchResult.TerminalStatus.SUCCESS
                    && status != ToolCallBatchResult.TerminalStatus.REJECTED) {
                continue;
            }
            ToolResponseMessage.ToolResponse response = responsesById.get(record.getToolCallId());
            if (response == null) {
                toolExecutionService.afterToolFailure(
                        executionContext,
                        record,
                        new IllegalStateException("Tool response missing for call " + record.getToolCallId()),
                        false
                );
                record.setTerminalRecorded(true);
                continue;
            }
            toolExecutionService.afterToolSuccess(
                    executionContext,
                    record,
                    response.responseData()
            );
            record.setTerminalRecorded(true);
        }
    }

    private final class RuntimeTimeoutToolCallback implements ToolCallback {
        private final String actualToolName;
        private final Deque<AssistantMessage.ToolCall> pendingCalls;
        private final ToolCallback delegate;
        private final ToolExecutionContext executionContext;
        private final List<ToolExecutionRecord> records;
        private final Map<String, TerminalResponse> terminalResponses;
        private final ToolDefinition executionDefinition;

        private RuntimeTimeoutToolCallback(String actualToolName,
                                           Deque<AssistantMessage.ToolCall> pendingCalls,
                                           ToolCallback delegate,
                                           ToolExecutionContext executionContext,
                                           List<ToolExecutionRecord> records,
                                           Map<String, TerminalResponse> terminalResponses) {
            this.actualToolName = actualToolName;
            this.pendingCalls = pendingCalls;
            this.delegate = delegate;
            this.executionContext = executionContext;
            this.records = records;
            this.terminalResponses = terminalResponses;
            ToolDefinition delegateDefinition = delegate == null ? null : delegate.getToolDefinition();
            this.executionDefinition = ToolDefinition.builder()
                    .name(actualToolName)
                    .description(delegateDefinition == null ? "Runtime validation for requested tool" : delegateDefinition.description())
                    .inputSchema(delegateDefinition == null ? UNKNOWN_TOOL_SCHEMA : delegateDefinition.inputSchema())
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return executionDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate == null ? ToolMetadata.builder().build() : delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            AgentTaskControl cancellationControl = executionContext.getCancellationControl();
            if (cancellationControl != null) {
                cancellationControl.throwIfCancellationRequested();
            }
            AssistantMessage.ToolCall toolCall = pendingCalls.pollFirst();
            if (toolCall == null) {
                throw new ToolExecutionException("Missing requested tool call for " + actualToolName, null);
            }
            try {
                ToolExecutionRecord record = toolExecutionService.beforeToolCall(executionContext, toolCall);
                records.add(record);
                if (delegate == null) {
                    throw new ToolExecutionException("Tool callback not found for " + actualToolName, null);
                }

                TaskEvidenceState taskEvidenceState = executionContext.getTaskEvidenceState();
                if (TaskEvidenceState.CODE_SEARCH_TOOL_NAME.equals(record.getCanonicalToolName())
                        && taskEvidenceState != null
                        && taskEvidenceState.isCodeSearchBlocked()) {
                    String response = rejectCodeSearchWithoutNovelty(record, taskEvidenceState);
                    recordTerminal(toolCall, ToolCallBatchResult.TerminalStatus.REJECTED, response);
                    return response;
                }

                ToolDuplicateCallDetector.DuplicateCheck duplicateCheck = duplicateCallDetector.check(
                        executionContext.getDuplicateCallState(),
                        record.getCanonicalToolName(),
                        toolCall.arguments());
                if (duplicateCheck.rejected()) {
                    String response = rejectDuplicateCall(record, duplicateCheck);
                    recordTerminal(toolCall, ToolCallBatchResult.TerminalStatus.REJECTED, response);
                    return response;
                }

                Duration timeout = timeoutProperties.timeoutFor(
                        record.getActualToolName(), record.getCanonicalToolName());
                Future<String> future = toolExecutor.submit(() -> delegate.call(toolInput, toolContext));
                if (cancellationControl != null) {
                    cancellationControl.attachToolFuture(future);
                }
                try {
                    String rawResult = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    if (cancellationControl != null && cancellationControl.isCancellationRequested()) {
                        toolExecutionService.afterToolCancellation(executionContext, record);
                        record.setTerminalRecorded(true);
                        throw new AgentTaskCancelledException(executionContext.getTaskId());
                    }
                    final String[] acceptedResult = new String[1];
                    Runnable success = () -> {
                        acceptedResult[0] = rawResult;
                    };
                    boolean accepted = cancellationControl == null
                            ? runSuccess(success)
                            : cancellationControl.runIfActive(success);
                    if (!accepted) {
                        toolExecutionService.afterToolCancellation(executionContext, record);
                        record.setTerminalRecorded(true);
                        throw new AgentTaskCancelledException(executionContext.getTaskId());
                    }
                    recordTerminal(toolCall, ToolCallBatchResult.TerminalStatus.SUCCESS, acceptedResult[0]);
                    return acceptedResult[0];
                } catch (TimeoutException e) {
                    boolean cancelRequested = future.cancel(true);
                    ToolTimeoutException timeoutError = new ToolTimeoutException(
                            "Tool '" + record.getCanonicalToolName() + "' exceeded runtime timeout of "
                                    + timeout.toMillis() + " ms; interrupt/cancel requested=" + cancelRequested
                                    + ", Agent Task will stop",
                            e);
                    toolExecutionService.afterToolFailure(executionContext, record, timeoutError, false);
                    record.setTerminalRecorded(true);
                    log.warn("Tool runtime timeout: taskId={}, toolName={}, timeoutMs={}, cancelRequested={}",
                            executionContext.getTaskId(), record.getCanonicalToolName(), timeout.toMillis(), cancelRequested);
                    throw timeoutError;
                } catch (CancellationException e) {
                    if (cancellationControl != null && cancellationControl.isCancellationRequested()) {
                        toolExecutionService.afterToolCancellation(executionContext, record);
                        record.setTerminalRecorded(true);
                        throw new AgentTaskCancelledException(executionContext.getTaskId());
                    }
                    throw new ToolExecutionException("Tool Future was cancelled", e);
                } catch (InterruptedException e) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    if (cancellationControl != null && cancellationControl.isCancellationRequested()) {
                        toolExecutionService.afterToolCancellation(executionContext, record);
                        record.setTerminalRecorded(true);
                        throw new AgentTaskCancelledException(executionContext.getTaskId());
                    }
                    throw new ToolExecutionException("Agent thread interrupted while waiting for tool "
                            + record.getCanonicalToolName(), e);
                } catch (ExecutionException e) {
                    resetDuplicateSequenceForCorrectableFailure(e.getCause());
                    throw propagate(e.getCause());
                } finally {
                    if (cancellationControl != null) {
                        cancellationControl.detachToolFuture(future);
                    }
                }
            } catch (RuntimeException error) {
                terminalResponses.putIfAbsent(toolCall.id(), failureResponse(toolCall, error));
                throw error;
            }
        }

        private void recordTerminal(AssistantMessage.ToolCall call,
                                    ToolCallBatchResult.TerminalStatus status,
                                    String responseData) {
            terminalResponses.put(call.id(), new TerminalResponse(status,
                    new ToolResponseMessage.ToolResponse(call.id(), call.name(), responseData)));
        }

        private boolean runSuccess(Runnable success) {
            success.run();
            return true;
        }

        private void resetDuplicateSequenceForCorrectableFailure(Throwable error) {
            boolean correctable = error instanceof ToolFailureException failure && failure.isCorrectable();
            if (!correctable && !(error instanceof IllegalArgumentException)) {
                return;
            }
            ToolDuplicateCallState state = executionContext.getDuplicateCallState();
            if (state != null) {
                state.resetSequence();
            }
        }

        private String rejectDuplicateCall(ToolExecutionRecord record,
                                           ToolDuplicateCallDetector.DuplicateCheck duplicateCheck) {
            ToolDuplicateCallException error = new ToolDuplicateCallException(
                    record.getCanonicalToolName(),
                    duplicateCheck.consecutiveCount(),
                    duplicateCheck.maxConsecutiveSameCalls(),
                    duplicateCheck.hardStop());
            toolExecutionService.afterToolFailure(executionContext, record, error, false);
            record.setTerminalRecorded(true);

            String feedback = "TOOL_CALL_REJECTED:\n"
                    + "reason=DUPLICATE_TOOL_CALL\n"
                    + "tool=" + record.getCanonicalToolName() + "\n"
                    + "consecutiveCount=" + duplicateCheck.consecutiveCount() + "\n"
                    + "hardStop=" + duplicateCheck.hardStop() + "\n"
                    + "message=This tool was called repeatedly with the same arguments. "
                    + "Use the existing result or change the tool or arguments.";
            log.warn("Duplicate tool call rejected: taskId={}, toolName={}, consecutiveCount={}, hardStop={}",
                    executionContext.getTaskId(), record.getCanonicalToolName(),
                    duplicateCheck.consecutiveCount(), duplicateCheck.hardStop());
            return feedback;
        }

        private String rejectCodeSearchWithoutNovelty(ToolExecutionRecord record,
                                                      TaskEvidenceState taskEvidenceState) {
            taskEvidenceState.recordGuardedSearchRequest();
            String feedback = "CODE_SEARCH_NO_NOVELTY_GUARD:\n"
                    + "reason=CONSECUTIVE_NO_NEW_EVIDENCE\n"
                    + "message=Code search stopped because the previous two searches produced no new evidence. "
                    + "Use the evidence already collected and proceed to Final unless another non-code-search "
                    + "tool is genuinely required by the user's request.";
            log.warn("Code search no-novelty guard rejected retrieval: taskId={}, toolName={}, searchCallCount={}",
                    executionContext.getTaskId(), record.getCanonicalToolName(),
                    taskEvidenceState.snapshot().searchCallCount());
            return feedback;
        }

        private RuntimeException propagate(Throwable error) {
            if (error instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            return new ToolExecutionException(error == null ? "Tool execution failed" : error.getMessage(), error);
        }
    }

    private void applyResultMetrics(ToolExecutionRecord record,
                                    ToolResultGuard.GuardedToolResult guarded) {
        if (record == null || record.isResultGuardApplied()) {
            return;
        }
        record.setResultGuardApplied(true);
        record.setOriginalResultChars(guarded.originalChars());
        record.setStoredResultChars(guarded.storedChars());
        record.setMaxResultChars(guarded.maxResultChars());
        record.setRuntimeResultTruncated(guarded.truncated());
    }

    private void publishProjectedToolResult(
            ToolExecutionContext context,
            ToolCallBatchResult batchResult,
            ToolExecutionRecord record,
            ToolResponseMessage.ToolResponse persistentResponse,
            ToolResultGuard.GuardedToolResult guarded) {
        if (context == null || record == null) {
            return;
        }
        ToolCallBatchResult.TerminalStatus terminalStatus = batchResult.getTerminalStatuses() == null
                ? null : batchResult.getTerminalStatuses().get(persistentResponse.id());
        if (terminalStatus != ToolCallBatchResult.TerminalStatus.SUCCESS
                && terminalStatus != ToolCallBatchResult.TerminalStatus.REJECTED) {
            return;
        }
        AgentLifecycleObservationPublisher.publishToolResult(
                new AgentLifecycleObservationPublisher.ToolResultObservation(
                        context.getTaskId(), context.getSessionId(), record.getToolCallId(),
                        record.getCanonicalToolName(), record.getActualToolName(),
                        persistentResponse.responseData(), guarded.value(), guarded.originalChars(),
                        guarded.storedChars(), guarded.truncated(),
                        observationStatus(persistentResponse.responseData(), terminalStatus)));
    }

    private String observationStatus(
            String persistentResult,
            ToolCallBatchResult.TerminalStatus terminalStatus) {
        return persistentResult != null && persistentResult.startsWith("CODE_SEARCH_NO_NOVELTY_GUARD:")
                ? "GUARDED" : terminalStatus.name();
    }

    private int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private record TerminalResponse(ToolCallBatchResult.TerminalStatus status,
                                    ToolResponseMessage.ToolResponse response) {
    }

    private record CompleteTerminalBatch(ToolResponseMessage message,
                                         Map<String, ToolCallBatchResult.TerminalStatus> statuses) {
    }
}
