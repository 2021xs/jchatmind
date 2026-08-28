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
import com.kama.jchatmind.tool.ToolRegistry;
import com.kama.jchatmind.tool.ToolTimeoutException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class ToolCallBatchExecutor {
    private static final String UNKNOWN_TOOL_SCHEMA = "{\"type\":\"object\"}";

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
        List<ToolExecutionRecord> records = new ArrayList<>();
        Prompt executionPrompt = withTimeoutCallbacks(prompt, chatResponse, executionContext, records);

        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(executionPrompt, chatResponse);
        } catch (IllegalArgumentException e) {
            return failed(records, new ToolArgumentException(e.getMessage(), e));
        } catch (RuntimeException e) {
            return failed(records, e);
        } catch (Exception e) {
            return failed(records, new ToolExecutionException(e.getMessage(), e));
        }

        toolExecutionResult = guardToolExecutionResult(toolExecutionResult, records);
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);
        recordUnfinishedToolResponses(executionContext, records, toolResponseMessage);
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.SUCCESS)
                .records(records)
                .toolExecutionResult(toolExecutionResult)
                .toolResponseMessage(toolResponseMessage)
                .build();
    }

    private ToolExecutionResult guardToolExecutionResult(ToolExecutionResult result,
                                                         List<ToolExecutionRecord> records) {
        List<Message> history = new ArrayList<>(result.conversationHistory());
        int lastIndex = history.size() - 1;
        if (lastIndex < 0 || !(history.get(lastIndex) instanceof ToolResponseMessage responseMessage)) {
            return result;
        }

        List<ToolResponseMessage.ToolResponse> guardedResponses = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> responses = responseMessage.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            ToolResponseMessage.ToolResponse response = responses.get(i);
            ToolExecutionRecord record = i < records.size() ? records.get(i) : null;
            String actualToolName = record == null ? response.name() : record.getActualToolName();
            String canonicalToolName = record == null
                    ? toolRegistry.canonicalName(response.name())
                    : record.getCanonicalToolName();
            ToolResultGuard.GuardedToolResult guarded = toolResultGuard.guard(
                    actualToolName, canonicalToolName, response.responseData());
            applyResultMetrics(record, guarded);
            guardedResponses.add(new ToolResponseMessage.ToolResponse(
                    response.id(), response.name(), guarded.value()));
        }

        ToolResponseMessage guardedMessage = ToolResponseMessage.builder()
                .responses(guardedResponses)
                .metadata(responseMessage.getMetadata())
                .build();
        history.set(lastIndex, guardedMessage);
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(result.returnDirect())
                .build();
    }

    private ToolCallBatchResult failed(List<ToolExecutionRecord> records, RuntimeException error) {
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.FAILED)
                .records(records)
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
                                        ChatResponse chatResponse,
                                        ToolExecutionContext executionContext,
                                        List<ToolExecutionRecord> records) {
        List<ToolCallback> availableCallbacks = callbacksFrom(prompt);
        Map<String, Object> toolContext = toolContextFrom(prompt);
        List<ToolCallback> timeoutCallbacks = timeoutCallbacks(
                requestedToolCalls(chatResponse), availableCallbacks, executionContext, records);
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

    private List<ToolCallback> timeoutCallbacks(List<AssistantMessage.ToolCall> toolCalls,
                                                List<ToolCallback> availableCallbacks,
                                                ToolExecutionContext executionContext,
                                                List<ToolExecutionRecord> records) {
        Map<String, Deque<AssistantMessage.ToolCall>> callsByActualName = new LinkedHashMap<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            callsByActualName.computeIfAbsent(toolCall.name(), ignored -> new ArrayDeque<>()).add(toolCall);
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        for (Map.Entry<String, Deque<AssistantMessage.ToolCall>> entry : callsByActualName.entrySet()) {
            String actualToolName = entry.getKey();
            ToolCallback delegate = findDelegate(actualToolName, availableCallbacks);
            callbacks.add(new RuntimeTimeoutToolCallback(
                    actualToolName, entry.getValue(), delegate, executionContext, records));
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
                                               ToolResponseMessage toolResponseMessage) {
        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
        for (int i = 0; i < responses.size() && i < records.size(); i++) {
            ToolExecutionRecord record = records.get(i);
            if (record.isTerminalRecorded()) {
                continue;
            }
            toolExecutionService.afterToolSuccess(
                    executionContext,
                    record,
                    responses.get(i).responseData()
            );
            record.setTerminalRecorded(true);
        }
        for (int i = responses.size(); i < records.size(); i++) {
            ToolExecutionRecord record = records.get(i);
            if (record.isTerminalRecorded()) {
                continue;
            }
            toolExecutionService.afterToolFailure(
                    executionContext,
                    record,
                    new IllegalStateException("Tool response missing for call index " + i),
                    false
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
        private final ToolDefinition executionDefinition;

        private RuntimeTimeoutToolCallback(String actualToolName,
                                           Deque<AssistantMessage.ToolCall> pendingCalls,
                                           ToolCallback delegate,
                                           ToolExecutionContext executionContext,
                                           List<ToolExecutionRecord> records) {
            this.actualToolName = actualToolName;
            this.pendingCalls = pendingCalls;
            this.delegate = delegate;
            this.executionContext = executionContext;
            this.records = records;
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

            ToolExecutionRecord record = toolExecutionService.beforeToolCall(executionContext, toolCall);
            records.add(record);
            if (delegate == null) {
                throw new ToolExecutionException("Tool callback not found for " + actualToolName, null);
            }

            TaskEvidenceState taskEvidenceState = executionContext.getTaskEvidenceState();
            if (TaskEvidenceState.CODE_SEARCH_TOOL_NAME.equals(record.getCanonicalToolName())
                    && taskEvidenceState != null
                    && taskEvidenceState.isCodeSearchBlocked()) {
                return rejectCodeSearchWithoutNovelty(record, taskEvidenceState);
            }

            ToolDuplicateCallDetector.DuplicateCheck duplicateCheck = duplicateCallDetector.check(
                    executionContext.getDuplicateCallState(),
                    record.getCanonicalToolName(),
                    toolCall.arguments());
            if (duplicateCheck.rejected()) {
                return rejectDuplicateCall(record, duplicateCheck);
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
                    ToolResultGuard.GuardedToolResult guarded = toolResultGuard.guard(
                            record.getActualToolName(), record.getCanonicalToolName(), rawResult);
                    applyResultMetrics(record, guarded);
                    acceptedResult[0] = guarded.value();
                    publishToolResult(executionContext, record, rawResult, guarded, "SUCCESS");
                    toolExecutionService.afterToolSuccess(executionContext, record, acceptedResult[0]);
                    record.setTerminalRecorded(true);
                };
                boolean accepted = cancellationControl == null
                        ? runSuccess(success)
                        : cancellationControl.runIfActive(success);
                if (!accepted) {
                    toolExecutionService.afterToolCancellation(executionContext, record);
                    record.setTerminalRecorded(true);
                    throw new AgentTaskCancelledException(executionContext.getTaskId());
                }
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
            ToolResultGuard.GuardedToolResult guarded = toolResultGuard.guard(
                    record.getActualToolName(), record.getCanonicalToolName(), feedback);
            applyResultMetrics(record, guarded);
            publishToolResult(executionContext, record, feedback, guarded, "REJECTED");
            log.warn("Duplicate tool call rejected: taskId={}, toolName={}, consecutiveCount={}, hardStop={}",
                    executionContext.getTaskId(), record.getCanonicalToolName(),
                    duplicateCheck.consecutiveCount(), duplicateCheck.hardStop());
            return guarded.value();
        }

        private String rejectCodeSearchWithoutNovelty(ToolExecutionRecord record,
                                                      TaskEvidenceState taskEvidenceState) {
            taskEvidenceState.recordGuardedSearchRequest();
            String feedback = "CODE_SEARCH_NO_NOVELTY_GUARD:\n"
                    + "reason=CONSECUTIVE_NO_NEW_EVIDENCE\n"
                    + "message=Code search stopped because the previous two searches produced no new evidence. "
                    + "Use the evidence already collected and proceed to Final unless another non-code-search "
                    + "tool is genuinely required by the user's request.";
            ToolResultGuard.GuardedToolResult guarded = toolResultGuard.guard(
                    record.getActualToolName(), record.getCanonicalToolName(), feedback);
            applyResultMetrics(record, guarded);
            publishToolResult(executionContext, record, feedback, guarded, "GUARDED");
            toolExecutionService.afterToolSuccess(executionContext, record, guarded.value());
            record.setTerminalRecorded(true);
            log.warn("Code search no-novelty guard rejected retrieval: taskId={}, toolName={}, searchCallCount={}",
                    executionContext.getTaskId(), record.getCanonicalToolName(),
                    taskEvidenceState.snapshot().searchCallCount());
            return guarded.value();
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

    private void publishToolResult(ToolExecutionContext context,
                                   ToolExecutionRecord record,
                                   String rawResult,
                                   ToolResultGuard.GuardedToolResult guarded,
                                   String status) {
        AgentLifecycleObservationPublisher.publishToolResult(
                new AgentLifecycleObservationPublisher.ToolResultObservation(
                        context.getTaskId(), context.getSessionId(), record.getToolCallId(),
                        record.getCanonicalToolName(), record.getActualToolName(),
                        rawResult, guarded.value(), guarded.originalChars(), guarded.storedChars(),
                        guarded.truncated(), status));
    }
}
