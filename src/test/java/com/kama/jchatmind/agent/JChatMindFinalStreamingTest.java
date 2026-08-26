package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.message.SseMessage;
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
import com.kama.jchatmind.tool.ToolFailureClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindFinalStreamingTest {

    @Test
    void featureDisabledKeepsValidatedDurableFinalizationAndOnlyDisablesTokenReplay() {
        Harness harness = new Harness(List.of(answerResponse("planning draft")),
                List.of(Flux.just(answerResponse("durable answer"))));

        harness.agent.run();

        verify(harness.requestSpec, times(1)).call();
        verify(harness.requestSpec, times(1)).stream();
        verify(harness.finalCompletionService, times(1)).complete(any());
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent)
                .containsExactly("durable answer");
        assertThat(harness.events).noneMatch(event -> event.type() == AgentSseEvent.Type.TOKEN
                || event.type() == AgentSseEvent.Type.FINAL_MESSAGE_START
                || event.type() == AgentSseEvent.Type.FINAL_MESSAGE_DONE);
        assertThat(harness.eventTypes()).contains(AgentSseEvent.Type.DONE);
        assertThat(harness.timeline).containsSubsequence(
                "durable:start", "persist:durable answer", "durable:commit", "message:full");

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(harness.chatClient, times(2)).prompt(prompts.capture());
        ToolCallingChatOptions finalOptions = (ToolCallingChatOptions) prompts.getAllValues().get(1).getOptions();
        assertThat(finalOptions.getToolCallbacks()).isEmpty();
        assertThat(finalOptions.getToolNames()).isEmpty();
        assertThat(finalOptions.getInternalToolExecutionEnabled()).isFalse();
    }

    @Test
    void normalFinalStreamUsesBlockingPlanningFiltersReasoningAndPersistsOnce() {
        Flux<ChatResponse> finalStream = Flux.just(
                response(DeepSeekAssistantMessage.prefixAssistantMessage("A", "secret-thinking")),
                answerResponse("B"),
                answerResponse("C"));
        Harness harness = new Harness(List.of(answerResponse("planning draft must be discarded")),
                List.of(finalStream), List.of(callback("searchEvidence")));
        harness.agent.setFinalStreamingEnabled(true);

        harness.agent.run();

        verify(harness.requestSpec, times(1)).call();
        verify(harness.requestSpec, times(1)).stream();
        ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(harness.requestSpec).toolCallbacks(callbacks.capture());
        assertThat(callbacks.getValue()).hasSize(1);

        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        verify(harness.requestSpec, times(1)).system(systemPrompts.capture());
        assertThat(systemPrompts.getValue())
                .contains("planning module")
                .contains("Do not draft, summarize, or repeat the final answer");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(harness.chatClient, times(2)).prompt(promptCaptor.capture());
        Prompt finalPrompt = promptCaptor.getAllValues().get(1);
        assertThat(finalPrompt.getInstructions().get(0).getText())
                .contains("user-visible final answer", "untrusted, read-only data")
                .doesNotContain("Available knowledge bases", "remaining", "next action");
        ToolCallingChatOptions finalOptions = (ToolCallingChatOptions) finalPrompt.getOptions();
        assertThat(finalOptions.getToolCallbacks()).isEmpty();
        assertThat(finalOptions.getToolNames()).isEmpty();
        assertThat(finalOptions.getToolContext()).isEmpty();
        assertThat(finalOptions.getInternalToolExecutionEnabled()).isFalse();
        List<Message> finalMessages = finalPrompt.getInstructions();
        assertThat(finalMessages).extracting(Message::getText)
                .doesNotContain("planning draft must be discarded");

        List<Event> tokens = harness.events.stream()
                .filter(event -> event.type() == AgentSseEvent.Type.TOKEN)
                .toList();
        assertThat(tokens).hasSize(3);
        assertThat(tokens).extracting(event -> event.payload().get("sequence"))
                .containsExactly(1, 2, 3);
        assertThat(tokens).extracting(event -> event.payload().get("delta"))
                .containsExactly("A", "B", "C")
                .doesNotContain("secret-thinking");
        assertThat(tokens).extracting(event -> event.payload().get("streamId")).doesNotContainNull();
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent).containsExactly("ABC");
        Event messageStart = harness.events.stream()
                .filter(event -> event.type() == AgentSseEvent.Type.FINAL_MESSAGE_START)
                .findFirst().orElseThrow();
        Event messageDoneEvent = harness.events.stream()
                .filter(event -> event.type() == AgentSseEvent.Type.FINAL_MESSAGE_DONE)
                .findFirst().orElseThrow();
        assertThat(messageStart.payload().get("phase")).isEqualTo("final_answer");
        assertThat(messageDoneEvent.payload().get("streamId")).isEqualTo(messageStart.payload().get("streamId"));
        assertThat(messageDoneEvent.payload().get("stepId")).isEqualTo(messageStart.payload().get("stepId"));
        assertThat(messageDoneEvent.payload().get("messageId")).isEqualTo("message-1");

        int start = harness.indexOf(AgentSseEvent.Type.FINAL_MESSAGE_START);
        int firstToken = harness.indexOf(AgentSseEvent.Type.TOKEN);
        int durableStarted = harness.timeline.indexOf("durable:start");
        int persisted = harness.timeline.indexOf("persist:ABC");
        int durableCommitted = harness.timeline.indexOf("durable:commit");
        int fullMessage = harness.timeline.indexOf("message:full");
        int messageDone = harness.indexOf(AgentSseEvent.Type.FINAL_MESSAGE_DONE);
        int done = harness.indexOf(AgentSseEvent.Type.DONE);
        assertTrue(start < durableStarted && durableStarted < persisted && persisted < durableCommitted
                && durableCommitted < firstToken && firstToken < fullMessage
                && fullMessage < messageDone && messageDone < done,
                harness.timeline.toString());
        assertThat(harness.finalCompletionCommands).singleElement().satisfies(command ->
                assertThat(command.finalStepSummary())
                        .contains("streamEventCount=3", "reasoningEventCount=1", "usage=UNAVAILABLE"));
    }

    @Test
    void zeroVisibleUnexpectedToolCallRetriesOnceAndPersistsSuccessfulAttempt() {
        Flux<ChatResponse> firstAttempt = Flux.just(
                response(DeepSeekAssistantMessage.prefixAssistantMessage("", "attempt-one-reasoning")),
                toolCallResponse("unexpected-1", "searchEvidence", "{}"));
        Flux<ChatResponse> secondAttempt = Flux.just(
                answerResponse("A"), answerResponse("B"), answerResponse("C"));
        Harness harness = new Harness(List.of(answerResponse("ready")),
                List.of(firstAttempt, secondAttempt));
        harness.agent.setFinalStreamingEnabled(true);

        harness.agent.run();

        verify(harness.requestSpec, times(2)).stream();
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(harness.chatClient, times(3)).prompt(prompts.capture());
        assertThat(prompts.getAllValues().get(1)).isNotSameAs(prompts.getAllValues().get(2));
        List<Message> firstFinalInstructions = prompts.getAllValues().get(1).getInstructions();
        List<Message> retryFinalInstructions = prompts.getAllValues().get(2).getInstructions();
        assertThat(firstFinalInstructions.get(firstFinalInstructions.size() - 1).getText())
                .doesNotContain("Corrective instruction");
        assertThat(retryFinalInstructions.get(retryFinalInstructions.size() - 1).getText())
                .contains("Corrective instruction", "previous attempt violated");
        ArgumentCaptor<ToolCallback[]> callbacks = ArgumentCaptor.forClass(ToolCallback[].class);
        verify(harness.requestSpec).toolCallbacks(callbacks.capture());
        assertThat(callbacks.getValue()).isEmpty();
        ToolCallingChatOptions finalOptions = (ToolCallingChatOptions) prompts.getAllValues().get(1).getOptions();
        assertThat(finalOptions.getToolCallbacks()).isEmpty();
        assertThat(finalOptions.getToolNames()).isEmpty();
        assertThat(finalOptions.getToolContext()).isEmpty();
        assertThat(harness.eventCount(AgentSseEvent.Type.FINAL_MESSAGE_START)).isEqualTo(1);
        assertThat(harness.eventCount(AgentSseEvent.Type.FINAL_MESSAGE_ABORT)).isZero();
        assertThat(harness.eventCount(AgentSseEvent.Type.FINAL_MESSAGE_DONE)).isEqualTo(1);
        assertThat(harness.eventCount(AgentSseEvent.Type.DONE)).isEqualTo(1);
        assertThat(harness.eventCount(AgentSseEvent.Type.ERROR)).isZero();
        List<Event> tokens = harness.events.stream()
                .filter(event -> event.type() == AgentSseEvent.Type.TOKEN)
                .toList();
        assertThat(tokens).extracting(event -> event.payload().get("sequence"))
                .containsExactly(1, 2, 3);
        assertThat(tokens).extracting(event -> event.payload().get("delta"))
                .containsExactly("A", "B", "C");
        assertThat(tokens).extracting(event -> event.payload().get("streamId")).containsOnly(
                harness.events.stream()
                        .filter(event -> event.type() == AgentSseEvent.Type.FINAL_MESSAGE_START)
                        .findFirst().orElseThrow().payload().get("streamId"));
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent).containsExactly("ABC");
        verify(harness.finalCompletionService, times(1)).complete(any());
        assertThat(harness.finalCompletionCommands).singleElement().satisfies(command ->
                assertThat(command.finalStepSummary())
                        .contains("finalAttemptCount=2", "unexpectedFinalToolCallRetrySucceeded=true"));
    }

    @Test
    void zeroVisibleUnexpectedToolCallTwiceFailsWithoutThirdAttempt() {
        Harness harness = new Harness(List.of(answerResponse("ready")), List.of(
                Flux.just(toolCallResponse("unexpected-1", "searchEvidence", "{}")),
                Flux.just(toolCallResponse("unexpected-2", "searchEvidence", "{}"))));
        harness.agent.setFinalStreamingEnabled(true);

        assertThrows(RuntimeException.class, harness.agent::run);

        verify(harness.requestSpec, times(2)).stream();
        assertThat(harness.eventCount(AgentSseEvent.Type.FINAL_MESSAGE_START)).isEqualTo(1);
        assertThat(harness.eventCount(AgentSseEvent.Type.FINAL_MESSAGE_ABORT)).isEqualTo(1);
        assertThat(harness.eventCount(AgentSseEvent.Type.ERROR)).isEqualTo(1);
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.TOKEN,
                AgentSseEvent.Type.FINAL_MESSAGE_DONE, AgentSseEvent.Type.DONE);
        assertThat(harness.persistedMessages).isEmpty();
        assertThat(harness.timeline).doesNotContain("message:full");
    }

    @Test
    void pseudoBatchThreeIsBufferedRejectedAndCorrectiveRetryAloneIsDelivered() {
        Flux<ChatResponse> leakingAttempt = Flux.just(
                answerResponse("[FINAL_EVI"),
                answerResponse("DENCE_BATCH]\nBatch: 3\ninternal evidence"));
        Harness harness = new Harness(List.of(answerResponse("ready")), List.of(
                leakingAttempt,
                Flux.just(answerResponse("A safe user-facing project summary."))));
        harness.agent.setFinalStreamingEnabled(true);

        harness.agent.run();

        verify(harness.requestSpec, times(2)).stream();
        assertThat(harness.events.stream()
                .filter(event -> event.type() == AgentSseEvent.Type.TOKEN)
                .map(event -> event.payload().get("delta")))
                .containsExactly("A safe user-facing project summary.")
                .noneMatch(value -> value.toString().contains("FINAL_EVIDENCE_BATCH"));
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent)
                .containsExactly("A safe user-facing project summary.");
        assertThat(harness.finalCompletionCommands).singleElement().satisfies(command ->
                assertThat(command.finalStepSummary())
                        .contains("finalValidationFailureCount=1", "finalCorrectiveRetrySucceeded=true"));

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(harness.chatClient, times(3)).prompt(prompts.capture());
        Prompt correctivePrompt = prompts.getAllValues().get(2);
        List<Message> correctiveInstructions = correctivePrompt.getInstructions();
        assertThat(correctiveInstructions.get(correctiveInstructions.size() - 1).getText())
                .contains("Corrective instruction")
                .doesNotContain("[FINAL_EVIDENCE_BATCH]", "internal evidence");
    }

    @Test
    void markerLeakageTwiceFailsAfterUnifiedBudgetWithoutTokenPersistenceOrTaskSuccess() {
        Flux<ChatResponse> first = Flux.just(answerResponse("[FINAL_EVIDENCE_BATCH]\nBatch: 3"));
        Flux<ChatResponse> second = Flux.just(answerResponse("<final_evidence_data>still invalid"));
        Harness harness = new Harness(List.of(answerResponse("ready")), List.of(first, second));
        harness.agent.setFinalStreamingEnabled(true);

        assertThrows(RuntimeException.class, harness.agent::run);

        verify(harness.requestSpec, times(2)).stream();
        verify(harness.batchExecutor, never()).execute(any(), any(), any(), any());
        verify(harness.logService, never()).finishTask(anyString(), anyString(), anyInt(), anyInt());
        assertThat(harness.eventCount(AgentSseEvent.Type.TOKEN)).isZero();
        assertThat(harness.eventTypes()).contains(AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                AgentSseEvent.Type.ERROR);
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.FINAL_MESSAGE_DONE,
                AgentSseEvent.Type.DONE);
        assertThat(harness.persistedMessages).isEmpty();
        assertThat(harness.timeline).doesNotContain("message:full");
    }

    @Test
    void bufferedTokensBeforeUnexpectedToolCallAreNotExposedAndShareCorrectiveRetryBudget() {
        Harness harness = new Harness(List.of(answerResponse("ready")), List.of(Flux.just(
                        answerResponse("A"), answerResponse("B"),
                        toolCallResponse("unexpected-1", "searchEvidence", "{}")),
                Flux.just(answerResponse("corrected answer"))));
        harness.agent.setFinalStreamingEnabled(true);

        harness.agent.run();

        verify(harness.requestSpec, times(2)).stream();
        assertThat(harness.events.stream()
                .filter(event -> event.type() == AgentSseEvent.Type.TOKEN)
                .map(event -> event.payload().get("delta"))).containsExactly("corrected answer");
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent)
                .containsExactly("corrected answer");
        assertThat(harness.eventCount(AgentSseEvent.Type.FINAL_MESSAGE_START)).isEqualTo(1);
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                AgentSseEvent.Type.ERROR);
    }

    @Test
    void cancellationBetweenAttemptsPreventsRetryRequest() {
        AgentTaskRuntimeRegistry registry = new AgentTaskRuntimeRegistry();
        AgentTaskControl control = registry.register("task-1", "session-1");
        Flux<ChatResponse> firstAttempt = Flux
                .just(toolCallResponse("unexpected-1", "searchEvidence", "{}"))
                .doOnCancel(control::requestCancellation);
        Harness harness = new Harness(List.of(answerResponse("ready")), List.of(
                firstAttempt, Flux.just(answerResponse("must not run"))));
        harness.agent.setFinalStreamingEnabled(true);
        harness.agent.setTaskRuntimeRegistry(registry);

        harness.agent.run();

        verify(harness.requestSpec, times(1)).stream();
        assertThat(harness.eventTypes()).containsSubsequence(
                AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                AgentSseEvent.Type.CANCELLED);
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.ERROR,
                AgentSseEvent.Type.FINAL_MESSAGE_DONE, AgentSseEvent.Type.DONE);
        assertThat(harness.persistedMessages).isEmpty();
    }

    @Test
    void cancellationDuringRetryAttemptDisposesCurrentSubscription() throws Exception {
        CountDownLatch retryStarted = new CountDownLatch(1);
        CountDownLatch retryCancelled = new CountDownLatch(1);
        Flux<ChatResponse> retryAttempt = Flux.<ChatResponse>never()
                .doOnSubscribe(ignored -> retryStarted.countDown())
                .doOnCancel(retryCancelled::countDown);
        Harness harness = new Harness(List.of(answerResponse("ready")), List.of(
                Flux.just(toolCallResponse("unexpected-1", "searchEvidence", "{}")),
                retryAttempt));
        harness.agent.setFinalStreamingEnabled(true);
        AgentTaskRuntimeRegistry registry = new AgentTaskRuntimeRegistry();
        AgentTaskControl control = registry.register("task-1", "session-1");
        harness.agent.setTaskRuntimeRegistry(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> run = executor.submit((Runnable) harness.agent::run);
            assertTrue(retryStarted.await(2, TimeUnit.SECONDS));

            assertThat(control.requestCancellation()).isEqualTo(AgentTaskControl.CancelResult.REQUESTED);
            run.get(2, TimeUnit.SECONDS);

            assertTrue(retryCancelled.await(1, TimeUnit.SECONDS));
            verify(harness.requestSpec, times(2)).stream();
            assertThat(harness.eventTypes()).containsSubsequence(
                    AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                    AgentSseEvent.Type.CANCELLED);
            assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.ERROR,
                    AgentSseEvent.Type.FINAL_MESSAGE_DONE, AgentSseEvent.Type.DONE);
            assertThat(harness.persistedMessages).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void streamErrorAbortsPartialAnswerAndFailsTask() {
        Harness harness = new Harness(List.of(answerResponse("ready")),
                List.of(Flux.concat(Flux.just(answerResponse("A"), answerResponse("B")),
                        Flux.error(new IllegalStateException("provider disconnected")))));
        harness.agent.setFinalStreamingEnabled(true);

        assertThrows(RuntimeException.class, harness.agent::run);

        assertThat(harness.persistedMessages).isEmpty();
        assertThat(harness.eventTypes()).containsSubsequence(
                AgentSseEvent.Type.FINAL_MESSAGE_START,
                AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                AgentSseEvent.Type.ERROR);
        assertThat(harness.eventCount(AgentSseEvent.Type.TOKEN)).isZero();
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.FINAL_MESSAGE_DONE,
                AgentSseEvent.Type.DONE);
        verify(harness.requestSpec, times(1)).stream();
        verify(harness.logService).failStepAndTask(anyString(), eq("task-1"), anyString(), anyInt(), anyInt());
    }

    @Test
    void emptyVisibleStreamAbortsWithoutPersistence() {
        Flux<ChatResponse> emptyAttempt = Flux.just(
                response(DeepSeekAssistantMessage.prefixAssistantMessage("", "reasoning-only")));
        Harness harness = new Harness(List.of(answerResponse("ready")),
                List.of(emptyAttempt, emptyAttempt));
        harness.agent.setFinalStreamingEnabled(true);

        assertThrows(RuntimeException.class, harness.agent::run);

        assertThat(harness.persistedMessages).isEmpty();
        assertThat(harness.eventTypes()).contains(AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                AgentSseEvent.Type.ERROR);
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.TOKEN,
                AgentSseEvent.Type.FINAL_MESSAGE_DONE, AgentSseEvent.Type.DONE);
        verify(harness.requestSpec, times(2)).stream();
    }

    @Test
    void cancellationDisposesFinalSubscriptionAndUsesCancelledLifecycle() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Harness harness = new Harness(List.of(answerResponse("ready")),
                List.of(Flux.<ChatResponse>never()
                        .doOnSubscribe(ignored -> subscribed.countDown())
                        .doOnCancel(cancelled::countDown)));
        harness.agent.setFinalStreamingEnabled(true);
        AgentTaskRuntimeRegistry registry = new AgentTaskRuntimeRegistry();
        AgentTaskControl control = registry.register("task-1", "session-1");
        harness.agent.setTaskRuntimeRegistry(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> run = executor.submit((Runnable) () -> harness.agent.run());
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            assertThat(control.requestCancellation()).isEqualTo(AgentTaskControl.CancelResult.REQUESTED);
            run.get(2, TimeUnit.SECONDS);

            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            assertThat(harness.persistedMessages).isEmpty();
            assertThat(harness.eventTypes()).containsSubsequence(
                    AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                    AgentSseEvent.Type.CANCELLED);
            assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.FINAL_MESSAGE_DONE,
                    AgentSseEvent.Type.DONE, AgentSseEvent.Type.ERROR);
            verify(harness.messageService).discardTaskToolMessages("session-1", "task-1");
            verify(harness.logService).cancelStepAndTask(anyString(), eq("task-1"), anyInt(), anyInt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationAfterCompletedToolBatchDiscardsTaskToolMemory() throws Exception {
        CountDownLatch finalSubscribed = new CountDownLatch(1);
        ToolCallback search = callback("searchEvidence");
        Harness harness = new Harness(
                List.of(toolCallResponse("call-1", "searchEvidence", "{}"), answerResponse("ready")),
                List.of(Flux.<ChatResponse>never().doOnSubscribe(ignored -> finalSubscribed.countDown())),
                List.of(search));
        when(harness.batchExecutor.execute(any(), any(), any(), any()))
                .thenReturn(toolResult("searchEvidence", "retrieved evidence"));
        when(harness.messageService.discardTaskToolMessages("session-1", "task-1")).thenReturn(2);
        harness.agent.setFinalStreamingEnabled(true);
        AgentTaskRuntimeRegistry registry = new AgentTaskRuntimeRegistry();
        AgentTaskControl control = registry.register("task-1", "session-1");
        harness.agent.setTaskRuntimeRegistry(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> run = executor.submit((Runnable) harness.agent::run);
            assertTrue(finalSubscribed.await(2, TimeUnit.SECONDS));

            assertThat(control.requestCancellation()).isEqualTo(AgentTaskControl.CancelResult.REQUESTED);
            run.get(2, TimeUnit.SECONDS);

            assertThat(harness.persistedMessages)
                    .filteredOn(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                            && message.getMetadata() != null
                            && message.getMetadata().getToolCalls() != null
                            && !message.getMetadata().getToolCalls().isEmpty())
                    .hasSize(1)
                    .allMatch(message -> "task-1".equals(message.getMetadata().getTaskId()));
            assertThat(harness.persistedMessages)
                    .filteredOn(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL)
                    .hasSize(1)
                    .allMatch(message -> "task-1".equals(message.getMetadata().getTaskId()));
            verify(harness.messageService).discardTaskToolMessages("session-1", "task-1");
            assertThat(harness.events.stream()
                    .filter(event -> event.type() == AgentSseEvent.Type.CANCELLED)
                    .map(event -> event.payload().get("discardedToolMessages")))
                    .containsExactly(2);
            assertThat(harness.eventTypes()).doesNotContain(
                    AgentSseEvent.Type.FINAL_MESSAGE_DONE, AgentSseEvent.Type.DONE, AgentSseEvent.Type.ERROR);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationDuringToolExecutionDiscardsPersistedAssistantToolCall() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch releaseToolExecutor = new CountDownLatch(1);
        ToolCallback search = callback("searchEvidence");
        Harness harness = new Harness(
                List.of(toolCallResponse("call-1", "searchEvidence", "{}")),
                List.of(),
                List.of(search));
        when(harness.batchExecutor.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            com.kama.jchatmind.tool.ToolExecutionContext context = invocation.getArgument(3);
            toolStarted.countDown();
            try {
                releaseToolExecutor.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.getCancellationControl().throwIfCancellationRequested();
            return toolResult("searchEvidence", "must not be persisted");
        });
        when(harness.messageService.discardTaskToolMessages("session-1", "task-1")).thenReturn(1);
        AgentTaskRuntimeRegistry registry = new AgentTaskRuntimeRegistry();
        AgentTaskControl control = registry.register("task-1", "session-1");
        harness.agent.setTaskRuntimeRegistry(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> run = executor.submit((Runnable) harness.agent::run);
            assertTrue(toolStarted.await(2, TimeUnit.SECONDS));

            assertThat(control.requestCancellation()).isEqualTo(AgentTaskControl.CancelResult.REQUESTED);
            releaseToolExecutor.countDown();
            run.get(2, TimeUnit.SECONDS);

            assertThat(harness.persistedMessages)
                    .filteredOn(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT)
                    .singleElement()
                    .satisfies(message -> {
                        assertThat(message.getMetadata().getTaskId()).isEqualTo("task-1");
                        assertThat(message.getMetadata().getToolCalls()).hasSize(1);
                    });
            assertThat(harness.persistedMessages)
                    .noneMatch(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL);
            verify(harness.messageService).discardTaskToolMessages("session-1", "task-1");
            assertThat(harness.events.stream()
                    .filter(event -> event.type() == AgentSseEvent.Type.CANCELLED)
                    .map(event -> event.payload().get("discardedToolMessages")))
                    .containsExactly(1);
        } finally {
            releaseToolExecutor.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void terminateToolTransitionsDirectlyFromToolLoopToFinalStream() {
        ToolCallback terminate = callback("terminate");
        Harness harness = new Harness(
                List.of(toolCallResponse("call-1", "terminate", "{}")),
                List.of(Flux.just(answerResponse("final from tool evidence"))),
                List.of(terminate));
        when(harness.batchExecutor.execute(any(), any(), any(), any())).thenReturn(terminateResult());
        harness.agent.setFinalStreamingEnabled(true);

        harness.agent.run();

        verify(harness.requestSpec, times(1)).call();
        verify(harness.requestSpec, times(1)).stream();
        verify(harness.batchExecutor, times(1)).execute(any(), any(), any(), any());
        assertThat(harness.startedStepTypes).containsExactly("THINK", "TOOL_CALL", "FINAL_SYNTHESIS", "FINISH");
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent)
                .contains("final from tool evidence");
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(harness.chatClient, times(2)).prompt(prompts.capture());
        List<Message> finalMessages = prompts.getAllValues().get(1).getInstructions();
        assertThat(finalMessages).noneMatch(ToolResponseMessage.class::isInstance);
        assertThat(finalMessages).noneMatch(ToolResponseMessage.class::isInstance);
        Message finalInstruction = finalMessages.get(finalMessages.size() - 1);
        assertThat(finalInstruction).isInstanceOf(UserMessage.class);
        assertThat(finalInstruction.getText()).contains("tool=\"terminate\"", "terminated");
    }

    @Test
    void planningKeepsToolProtocolWhileFinalUsesPlainEvidenceContext() {
        ToolCallback search = callback("searchEvidence");
        Harness harness = new Harness(
                List.of(toolCallResponse("call-1", "searchEvidence", "{}"), answerResponse("ready")),
                List.of(Flux.just(answerResponse("final answer"))),
                List.of(search));
        when(harness.batchExecutor.execute(any(), any(), any(), any()))
                .thenReturn(toolResult("searchEvidence", "retrieved evidence"));
        harness.agent.setFinalStreamingEnabled(true);

        harness.agent.run();

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(harness.chatClient, times(3)).prompt(prompts.capture());
        List<Message> nextPlanningMessages = prompts.getAllValues().get(1).getInstructions();
        assertThat(nextPlanningMessages).anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(nextPlanningMessages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .flatMap(message -> message.getToolCalls().stream()))
                .hasSize(1);

        List<Message> finalMessages = prompts.getAllValues().get(2).getInstructions();
        assertThat(finalMessages).noneMatch(ToolResponseMessage.class::isInstance);
        assertThat(finalMessages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .flatMap(message -> message.getToolCalls().stream()))
                .isEmpty();
        Message finalInstruction = finalMessages.get(finalMessages.size() - 1);
        assertThat(finalInstruction).isInstanceOf(UserMessage.class);
        assertThat(finalInstruction.getText()).contains("retrieved evidence")
                .doesNotContain("[FINAL_EVIDENCE_BATCH]");
    }

    @Test
    void maxStepForcedFinalStartsStreamWithoutPlanningCall() {
        Harness harness = new Harness(List.of(), List.of(Flux.just(answerResponse("forced final"))));
        harness.agent.setFinalStreamingEnabled(true);
        harness.agent.setMaxLoopSteps(1);

        harness.agent.run();

        verify(harness.requestSpec, never()).call();
        verify(harness.requestSpec, times(1)).stream();
        assertThat(harness.startedStepTypes).containsExactly("FINAL_SYNTHESIS", "FINISH");
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent)
                .containsExactly("forced final");
    }

    @Test
    void duplicateHardStopTransitionsDirectlyToFinalStreamEvenAtLoopLimit() {
        ToolCallback search = callback("searchEvidence");
        Harness harness = new Harness(
                List.of(toolCallResponse("call-1", "searchEvidence", "{}")),
                List.of(Flux.just(answerResponse("forced duplicate final"))),
                List.of(search));
        when(harness.batchExecutor.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            com.kama.jchatmind.tool.ToolExecutionContext context = invocation.getArgument(3);
            context.getDuplicateCallState().observe("same", 1);
            context.getDuplicateCallState().observe("same", 1);
            context.getDuplicateCallState().observe("same", 1);
            return toolResult("searchEvidence", "evidence");
        });
        harness.agent.setFinalStreamingEnabled(true);
        harness.agent.setMaxLoopSteps(3);

        harness.agent.run();

        verify(harness.requestSpec, times(1)).call();
        verify(harness.requestSpec, times(1)).stream();
        assertThat(harness.startedStepTypes).containsExactly(
                "THINK", "TOOL_CALL", "FINAL_SYNTHESIS", "FINISH");
        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent)
                .contains("forced duplicate final");
    }

    @Test
    void durablePersistenceFailureEmitsNoTokenAndAbortsWithoutDone() {
        Harness harness = new Harness(List.of(answerResponse("ready")),
                List.of(Flux.just(answerResponse("complete but uncommitted"))));
        doThrow(new IllegalStateException("database unavailable"))
                .when(harness.finalCompletionService).complete(any());
        harness.agent.setFinalStreamingEnabled(true);

        assertThrows(RuntimeException.class, harness.agent::run);

        assertThat(harness.persistedMessages).isEmpty();
        assertThat(harness.timeline).doesNotContain("message:full");
        assertThat(harness.eventTypes()).containsSubsequence(
                AgentSseEvent.Type.FINAL_MESSAGE_START,
                AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                AgentSseEvent.Type.ERROR);
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.TOKEN,
                AgentSseEvent.Type.FINAL_MESSAGE_DONE, AgentSseEvent.Type.DONE);
        assertThat(harness.events).noneMatch(event -> event.type() == AgentSseEvent.Type.STEP_DONE
                && ("FINAL_SYNTHESIS".equals(event.payload().get("stepType"))
                || "FINISH".equals(event.payload().get("stepType"))));
        verify(harness.logService, never()).finishTask(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void taskFinalizationFailureRollsBackBeforeAnySuccessContentIsPublished() {
        Harness harness = new Harness(List.of(answerResponse("ready")),
                List.of(Flux.just(answerResponse("must remain uncommitted"))));
        doThrow(new IllegalStateException("task finalization failed"))
                .when(harness.finalCompletionService).complete(any());
        harness.agent.setFinalStreamingEnabled(true);

        assertThrows(RuntimeException.class, harness.agent::run);

        assertThat(harness.persistedMessages).isEmpty();
        assertThat(harness.timeline).doesNotContain("message:full");
        assertThat(harness.eventTypes()).contains(AgentSseEvent.Type.FINAL_MESSAGE_ABORT,
                AgentSseEvent.Type.ERROR);
        assertThat(harness.eventTypes()).doesNotContain(AgentSseEvent.Type.TOKEN,
                AgentSseEvent.Type.FINAL_MESSAGE_DONE, AgentSseEvent.Type.DONE);
        assertThat(harness.events).noneMatch(event -> event.type() == AgentSseEvent.Type.STEP_DONE
                && ("FINAL_SYNTHESIS".equals(event.payload().get("stepType"))
                || "FINISH".equals(event.payload().get("stepType"))));
    }

    @Test
    void disconnectedSseDoesNotCancelGenerationOrPersistence() {
        SseService disconnectedSse = mock(SseService.class);
        doThrow(new IllegalStateException("emitter closed"))
                .when(disconnectedSse).sendEvent(anyString(), any(AgentSseEvent.class));
        doThrow(new IllegalStateException("emitter closed"))
                .when(disconnectedSse).send(anyString(), any());
        Harness harness = new Harness(List.of(answerResponse("ready")),
                List.of(Flux.just(answerResponse("background answer"))));
        harness.replacePublisher(new AgentEventPublisher(disconnectedSse));
        harness.agent.setFinalStreamingEnabled(true);

        harness.agent.run();

        assertThat(harness.persistedMessages).extracting(ChatMessageDTO::getContent)
                .containsExactly("background answer");
        verify(harness.finalCompletionService).complete(any());
        verify(harness.logService, never()).failStepAndTask(any(), anyString(), anyString(), anyInt(), anyInt());
    }

    private static final class Harness {
        private final ChatClient chatClient = mock(ChatClient.class);
        private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        private final ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        private final AgentTaskLogService logService = mock(AgentTaskLogService.class);
        private final ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        private final FinalCompletionService finalCompletionService = mock(FinalCompletionService.class);
        private final ToolCallBatchExecutor batchExecutor = mock(ToolCallBatchExecutor.class);
        private final List<ChatMessageDTO> persistedMessages = new CopyOnWriteArrayList<>();
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final List<String> timeline = new CopyOnWriteArrayList<>();
        private final List<String> startedStepTypes = new CopyOnWriteArrayList<>();
        private final List<FinalCompletionService.FinalCompletionCommand> finalCompletionCommands =
                new CopyOnWriteArrayList<>();
        private final CountDownLatch finalStarted = new CountDownLatch(1);
        private AgentEventPublisher publisher;
        private JChatMind agent;

        private Harness(List<ChatResponse> calls, List<Flux<ChatResponse>> streams) {
            this(calls, streams, List.of());
        }

        private Harness(List<ChatResponse> calls, List<Flux<ChatResponse>> streams,
                        List<ToolCallback> callbacks) {
            Queue<ChatResponse> callQueue = new ArrayDeque<>(calls);
            Queue<Flux<ChatResponse>> streamQueue = new ArrayDeque<>(streams);
            when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
            when(requestSpec.system(anyString())).thenReturn(requestSpec);
            when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
            when(requestSpec.call()).thenReturn(callSpec);
            when(callSpec.chatClientResponse()).thenAnswer(ignored ->
                    new ChatClientResponse(callQueue.remove(), Map.of()));
            when(requestSpec.stream()).thenReturn(streamSpec);
            when(streamSpec.chatResponse()).thenAnswer(ignored -> streamQueue.remove());

            when(logService.startTask(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyInt(), anyString()))
                    .thenReturn(AgentTask.builder().id("task-1").build());
            AtomicInteger stepSequence = new AtomicInteger();
            when(logService.startStep(anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        String type = invocation.getArgument(2);
                        startedStepTypes.add(type);
                        return AgentStep.builder()
                                .id("step-" + stepSequence.incrementAndGet())
                                .stepNo(invocation.getArgument(1))
                                .stepType(type)
                                .build();
                    });
            AtomicInteger messageSequence = new AtomicInteger();
            when(messageService.createChatMessage(any(ChatMessageDTO.class))).thenAnswer(invocation -> {
                ChatMessageDTO message = invocation.getArgument(0);
                persistedMessages.add(message);
                timeline.add("persist:" + message.getContent());
                return CreateChatMessageResponse.builder()
                        .chatMessageId("message-" + messageSequence.incrementAndGet())
                        .build();
            });
            when(messageService.getChatMessageDTOsBySessionId(anyString())).thenReturn(List.of());
            when(finalCompletionService.complete(any())).thenAnswer(invocation -> {
                FinalCompletionService.FinalCompletionCommand command = invocation.getArgument(0);
                finalCompletionCommands.add(command);
                timeline.add("durable:start");
                ChatMessageDTO message = ChatMessageDTO.builder()
                        .id("message-" + messageSequence.incrementAndGet())
                        .sessionId(command.sessionId())
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content(command.finalAnswer())
                        .metadata(ChatMessageDTO.MetaData.builder().toolCalls(List.of()).build())
                        .build();
                persistedMessages.add(message);
                timeline.add("persist:" + message.getContent());
                startedStepTypes.add("FINISH");
                timeline.add("durable:commit");
                return new FinalCompletionService.FinalCompletionResult(
                        message.getId(), command.finalStepId(), command.finalStepNo(),
                        "finish-step-1", command.finishStepNo());
            });

            publisher = mockPublisher();
            buildAgent(callbacks);
            clearInvocations(chatClient, requestSpec, callSpec, streamSpec);
        }

        private void replacePublisher(AgentEventPublisher replacement) {
            publisher = replacement;
            buildAgent(List.of());
            clearInvocations(chatClient, requestSpec, callSpec, streamSpec);
        }

        private void buildAgent(List<ToolCallback> callbacks) {
            ConversationContextCompressor compressor = mock(ConversationContextCompressor.class);
            when(compressor.check(anyString(), anyString(), any()))
                    .thenReturn(new ConversationContextCompressor.CompressionCheck(
                            false, "not_needed", 0, 0, 0, 0, "TEST", 0, 0));
            agent = new JChatMind(
                    "agent-1", "test-model", "test-agent", "test", "system", chatClient, 20,
                    List.of(new UserMessage("question")), callbacks, List.of(), "session-1",
                    mock(SseService.class), publisher, mock(ToolExecutionService.class), messageService,
                    mock(ChatMessageConverter.class), logService, compressor, "user-message-1",
                    callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList(),
                    new ToolCorrectionProperties(), new ToolFailureClassifier(), null, batchExecutor);
            agent.setFinalCompletionService(finalCompletionService);
        }

        private AgentEventPublisher mockPublisher() {
            AgentEventPublisher mock = mock(AgentEventPublisher.class);
            doAnswer(invocation -> {
                AgentSseEvent.Type type = invocation.getArgument(2);
                Map<String, Object> payload = invocation.getArgument(3);
                events.add(new Event(type, payload));
                timeline.add("event:" + type.name());
                if (type == AgentSseEvent.Type.FINAL_MESSAGE_START) {
                    finalStarted.countDown();
                }
                return null;
            }).when(mock).publish(anyString(), anyString(), any(AgentSseEvent.Type.class), any());
            doAnswer(invocation -> {
                timeline.add("message:full");
                return null;
            }).when(mock).sendMessage(anyString(), any(SseMessage.class));
            return mock;
        }

        private int indexOf(AgentSseEvent.Type type) {
            return timeline.indexOf("event:" + type.name());
        }

        private List<AgentSseEvent.Type> eventTypes() {
            return events.stream().map(Event::type).toList();
        }

        private long eventCount(AgentSseEvent.Type type) {
            return events.stream().filter(event -> event.type() == type).count();
        }
    }

    private record Event(AgentSseEvent.Type type, Map<String, Object> payload) {
    }

    private static ChatResponse response(AssistantMessage output) {
        return new ChatResponse(List.of(new Generation(output)));
    }

    private static ChatResponse answerResponse(String content) {
        AssistantMessage output = AssistantMessage.builder().content(content).toolCalls(List.of()).build();
        return new ChatResponse(List.of(new Generation(output,
                ChatGenerationMetadata.builder().finishReason("STOP").build())));
    }

    private static ChatResponse toolCallResponse(String id, String name, String arguments) {
        return response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build());
    }

    private static ToolCallback callback(String name) {
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

    private static ToolCallBatchResult terminateResult() {
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "terminate", "terminated")))
                .build();
        List<Message> history = List.of(
                new UserMessage("question"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "terminate", "{}")))
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

    private static ToolCallBatchResult toolResult(String toolName, String responseData) {
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", toolName, responseData)))
                .build();
        List<Message> history = List.of(
                new UserMessage("question"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", toolName, "{}")))
                        .build(),
                response);
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.SUCCESS)
                .records(List.of(ToolExecutionRecord.builder()
                        .toolCallId("call-1").actualToolName(toolName).canonicalToolName(toolName).build()))
                .toolResponseMessage(response)
                .toolExecutionResult(org.springframework.ai.model.tool.ToolExecutionResult.builder()
                        .conversationHistory(history).returnDirect(false).build())
                .build();
    }
}
