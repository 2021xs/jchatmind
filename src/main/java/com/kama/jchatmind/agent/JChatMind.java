package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.tools.CodeChunkTools;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.FinalCompletionService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolDuplicateCallState;
import com.kama.jchatmind.tool.ToolExecutionException;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolFailureDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;
    private static final int MAX_FINAL_ATTEMPTS = 2;
    private static final String FINAL_PROVIDER_UNAVAILABLE = "UNAVAILABLE";

    private String agentId;
    private String model;
    private String name;
    private String description;
    private String systemPrompt;
    private ChatClient chatClient;
    private AgentState agentState;
    private List<ToolCallback> availableTools;
    private List<KnowledgeBaseDTO> availableKbs;
    private ToolCallingManager toolCallingManager;
    private ChatMemory chatMemory;
    private String chatSessionId;
    private ChatOptions chatOptions;
    private AgentEventPublisher agentEventPublisher;
    private AgentRunFailureHandler agentRunFailureHandler;
    private ToolCallBatchExecutor toolCallBatchExecutor;
    private ChatMessageConverter chatMessageConverter;
    private ChatMessageFacadeService chatMessageFacadeService;
    private ChatResponse lastChatResponse;
    private AgentTaskLogService agentTaskLogService;
    private ConversationContextCompressor conversationContextCompressor;
    private String userMessageId;
    private String originalUserQuestion;
    private String currentTaskId;
    private ConversationContextCompressor.ContinuationState continuationState =
            ConversationContextCompressor.ContinuationState.empty();
    private AgentStep currentStep;
    private AgentExecutionContext agentExecutionContext;
    private List<String> runtimeToolNames;
    private ToolCorrectionProperties toolCorrectionProperties = new ToolCorrectionProperties();
    private ToolFailureClassifier toolFailureClassifier = new ToolFailureClassifier();
    private int nextStepNo = 1;
    private int toolCallCount = 0;
    private int maxLoopSteps = MAX_STEPS;
    private String finishReason;
    private String traceId;
    private String trustedRepoId;
    private final Map<String, Integer> toolCorrectionAttempts = new HashMap<>();
    private final ToolDuplicateCallState duplicateCallState = new ToolDuplicateCallState();
    private final TaskEvidenceState taskEvidenceState = new TaskEvidenceState();
    private boolean forceFinalAnswer;
    private boolean finalizationRequired;
    private AgentTaskRuntimeRegistry taskRuntimeRegistry;
    private AgentTaskControl taskControl;
    private AssistantMessage pendingFinalAssistantMessage;
    private List<String> pendingFinalDeltas = List.of();
    private boolean finalStreamingEnabled;
    private FinalCompletionService finalCompletionService;
    private AgentStep pendingFinalSynthesisStep;
    private FinalStreamMetrics finalStreamMetrics;
    private String activeFinalStreamId;
    private boolean finalMessageAbortPublished;
    private boolean finalMessageDonePublished;
    private long taskStartedAtMs;
    private long finalLogicalRequestStartedAtMs;
    private int finalAttemptCount;
    private int unexpectedFinalToolCallCount;
    private int unexpectedFinalToolCallRetryCount;
    private boolean unexpectedFinalToolCallRetrySucceeded;
    private int finalValidationFailureCount;
    private int finalCorrectiveRetryCount;
    private boolean finalCorrectiveRetrySucceeded;
    private final FinalSynthesisRequestFactory finalSynthesisRequestFactory = new FinalSynthesisRequestFactory();
    private FinalContextCompiler finalContextCompiler = new FinalContextCompiler();
    private final FinalOutputValidator finalOutputValidator = new FinalOutputValidator();


    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String model,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ToolExecutionService toolExecutionService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     AgentTaskLogService agentTaskLogService,
                     ConversationContextCompressor conversationContextCompressor,
                     String userMessageId,
                     List<String> runtimeToolNames,
                     ToolCorrectionProperties toolCorrectionProperties,
                     ToolFailureClassifier toolFailureClassifier,
                     ToolCallBatchExecutor toolCallBatchExecutor) {
        this(agentId, model, name, description, systemPrompt, chatClient, maxMessages, memory,
                availableTools, availableKbs, chatSessionId, sseService, new AgentEventPublisher(sseService),
                toolExecutionService, chatMessageFacadeService, chatMessageConverter, agentTaskLogService,
                conversationContextCompressor, userMessageId, runtimeToolNames, toolCorrectionProperties,
                toolFailureClassifier, null, toolCallBatchExecutor);
    }

    public JChatMind(String agentId,
                     String model,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     AgentEventPublisher agentEventPublisher,
                     ToolExecutionService toolExecutionService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     AgentTaskLogService agentTaskLogService,
                     ConversationContextCompressor conversationContextCompressor,
                     String userMessageId,
                     List<String> runtimeToolNames,
                     ToolCorrectionProperties toolCorrectionProperties,
                     ToolFailureClassifier toolFailureClassifier,
                     AgentRunFailureHandler agentRunFailureHandler,
                     ToolCallBatchExecutor toolCallBatchExecutor) {
        this.agentId = agentId;
        this.model = model;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClient;
        this.availableTools = availableTools;
        this.availableKbs = availableKbs;
        this.chatSessionId = chatSessionId;
        this.agentEventPublisher = agentEventPublisher == null ? new AgentEventPublisher(sseService) : agentEventPublisher;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.agentTaskLogService = agentTaskLogService;
        this.agentRunFailureHandler = agentRunFailureHandler == null
                ? new AgentRunFailureHandler(agentTaskLogService, this.agentEventPublisher)
                : agentRunFailureHandler;
        Assert.notNull(toolCallBatchExecutor, "ToolCallBatchExecutor cannot be null");
        Assert.notNull(conversationContextCompressor, "ConversationContextCompressor cannot be null");
        this.toolCallBatchExecutor = toolCallBatchExecutor;
        this.conversationContextCompressor = conversationContextCompressor;
        this.userMessageId = userMessageId;
        this.originalUserQuestion = findLastUserQuestion(memory);
        this.runtimeToolNames = runtimeToolNames == null ? List.of() : runtimeToolNames;
        if (toolCorrectionProperties != null) {
            this.toolCorrectionProperties = toolCorrectionProperties;
        }
        if (toolFailureClassifier != null) {
            this.toolFailureClassifier = toolFailureClassifier;
        }
        this.agentState = AgentState.IDLE;

        this.chatMemory = new ProtocolAwareChatMemory(
                maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages);

        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }
        this.chatMemory.add(chatSessionId, memory);

        this.chatOptions = createPlanningChatOptions(null, null);
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] no tool calls");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format("[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1, call.name(), call.arguments());
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    private boolean sendAgentEvent(AgentSseEvent.Type type, Map<String, Object> payload) {
        return agentEventPublisher.publish(currentTaskId, this.chatSessionId, type, payload);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 4000) {
            return value;
        }
        return value.substring(0, 3968) + "\n...[truncated]";
    }

    private String summarizeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "no tool calls";
        }
        return toolCalls.stream()
                .map(call -> call.name() + "(" + truncate(call.arguments()) + ")")
                .collect(Collectors.joining("\n"));
    }

    private Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return payload;
    }

    private AgentStep startStep(String stepType, String inputSummary) {
        AgentStep step = agentTaskLogService.startStep(currentTaskId, nextStepNo++, stepType, inputSummary, model);
        currentStep = step;
        if (agentExecutionContext != null) {
            agentExecutionContext.setCurrentStepId(step.getId());
            agentExecutionContext.setStepNo(step.getStepNo());
        }
        return step;
    }

    private List<Message> toMemoryMessages(List<ChatMessageDTO> messages) {
        List<Message> memory = new ArrayList<>();
        for (ChatMessageDTO chatMessageDTO : messages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    }
                    break;
                case USER:
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(new org.springframework.ai.chat.messages.UserMessage(chatMessageDTO.getContent()));
                    }
                    break;
                case ASSISTANT:
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata() == null || chatMessageDTO.getMetadata().getToolCalls() == null
                                    ? List.of()
                                    : chatMessageDTO.getMetadata().getToolCalls())
                            .build());
                    break;
                case TOOL:
                    if (chatMessageDTO.getMetadata() == null || chatMessageDTO.getMetadata().getToolResponse() == null) {
                        log.warn("Skip tool message without tool response metadata during runtime compression: messageId={}", chatMessageDTO.getId());
                        break;
                    }
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO.getMetadata().getToolResponse()))
                            .build());
                    break;
                default:
                    throw new IllegalStateException("Unsupported message type: " + chatMessageDTO.getRole());
            }
        }
        return memory;
    }

    private List<ChatMessageDTO> currentTaskProtocolMessages(List<ChatMessageDTO> allMessages) {
        return allMessages.stream()
                .filter(message -> message != null && message.getMetadata() != null)
                .filter(message -> currentTaskId.equals(message.getMetadata().getTaskId()))
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL
                        || message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                        && message.getMetadata().getToolCalls() != null
                        && !message.getMetadata().getToolCalls().isEmpty())
                .toList();
    }

    private List<ChatMessageDTO> projectPersistentToolResponses(List<ChatMessageDTO> protocolMessages) {
        return toolCallBatchExecutor.projectPersistedProtocolForContext(model, protocolMessages);
    }

    private ChatMessageDTO currentUserMessage(List<ChatMessageDTO> allMessages) {
        return allMessages.stream()
                .filter(message -> userMessageId != null && userMessageId.equals(message.getId()))
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.USER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current task original User message is not available: messageId=" + userMessageId));
    }

    private List<Message> workingContextMessages(
            ConversationContextCompressor.CompletedConversationProjection conversation,
            ConversationContextCompressor.ContinuationStateCompression currentTask) {
        List<Message> memory = new ArrayList<>();
        if (StringUtils.hasLength(systemPrompt)) {
            memory.add(new SystemMessage(systemPrompt));
        }
        memory.addAll(AgentMemoryHistorySanitizer.toSafeReplayMessages(
                conversation.summary(), conversation.messages()));
        if (StringUtils.hasText(currentTask.state().content())) {
            memory.add(new SystemMessage(ConversationContextCompressor.continuationStateMessageContent(
                    currentTask.state().content())));
        }
        memory.addAll(toMemoryMessages(currentTask.uncoveredProtocolMessages()));
        return memory;
    }

    private void compressContextBeforeThinkIfNeeded(int loopStep) {
        if (conversationContextCompressor == null || currentTaskId == null) {
            return;
        }
        List<ChatMessageDTO> allMessages = chatMessageFacadeService.getChatMessageDTOsBySessionId(chatSessionId);
        List<ChatMessageDTO> persistentProtocol = currentTaskProtocolMessages(allMessages);
        if (persistentProtocol.isEmpty()) {
            return;
        }
        ConversationContextCompressor.CompletedConversationProjection conversation =
                conversationContextCompressor.projectCompletedConversation(
                        chatSessionId, model, userMessageId, allMessages);
        ChatMessageDTO currentUser = currentUserMessage(allMessages);
        List<ChatMessageDTO> projectedProtocol = projectPersistentToolResponses(
                persistentProtocol);
        List<ChatMessageDTO> fixedPlanningMessages = fixedPlanningMeasurementMessages(loopStep);
        ConversationContextCompressor.CompressionCheck check =
                conversationContextCompressor.checkCurrentTask(
                        model, currentUser, conversation.summary(), conversation.messages(),
                        projectedProtocol, fixedPlanningMessages, continuationState);
        if (!check.needed()) {
            return;
        }

        AgentStep compressionStep = startStep("CONTEXT_COMPRESSION",
                "reason=" + check.reason()
                        + ", messages=" + check.messageCount()
                        + ", rawHistoryTokens=" + check.rawHistoryTokens()
                        + ", effectiveContextTokens=" + check.effectiveContextTokens()
                        + ", maxToolResultTokens=" + check.maxSingleToolResultTokens()
                        + ", newCompressibleMessages=" + check.newCompressibleMessages());
        try {
            ConversationContextCompressor.ContinuationStateCompression compressedContext =
                    conversationContextCompressor.compressCurrentTaskIfNeeded(
                            chatSessionId, model, currentUser, conversation.summary(), conversation.messages(),
                            projectedProtocol, fixedPlanningMessages, continuationState);
            continuationState = compressedContext.state();
            if (compressedContext.compressed()) {
                this.chatMemory.clear(this.chatSessionId);
                this.chatMemory.add(this.chatSessionId, workingContextMessages(conversation, compressedContext));
            }
            agentTaskLogService.finishStep(compressionStep.getId(),
                    "compressed=" + compressedContext.compressed()
                            + ", stateChars=" + (compressedContext.state().content() == null
                            ? 0 : compressedContext.state().content().length())
                            + ", coveredThroughLogicalGroup="
                            + compressedContext.state().coveredThroughLogicalGroup()
                            + ", stateDepth=" + compressedContext.state().stateDepth()
                            + ", compressionCount=" + compressedContext.state().compressionCount()
                            + ", correctiveRetryCount=" + compressedContext.correctiveRetryCount()
                            + ", uncoveredProtocolMessages="
                            + compressedContext.uncoveredProtocolMessages().size());
            sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                    "stepId", compressionStep.getId(),
                    "stepNo", compressionStep.getStepNo(),
                    "stepType", compressionStep.getStepType(),
                    "status", AgentTaskLogService.STATUS_SUCCESS
            ));
        } catch (Exception e) {
            agentTaskLogService.failStep(compressionStep.getId(), e.getMessage());
            sendAgentEvent(AgentSseEvent.Type.ERROR, payload(
                    "stepId", compressionStep.getId(),
                    "stepNo", compressionStep.getStepNo(),
                    "errorMessage", truncate(e.getMessage())
            ));
            log.warn("Runtime context compression failed, continuing with current memory: taskId={}, error={}",
                    currentTaskId, e.getMessage(), e);
        }
    }

    private List<ChatMessageDTO> fixedPlanningMeasurementMessages(int loopStep) {
        List<ChatMessageDTO> fixed = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            fixed.add(ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.SYSTEM)
                    .content(systemPrompt)
                    .build());
        }
        String thinkPrompt = buildPlanningPrompt(this.availableKbs, loopStep, maxLoopSteps, toolCallCount,
                taskEvidenceState.snapshot());
        if (StringUtils.hasText(thinkPrompt)) {
            fixed.add(ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.SYSTEM)
                    .content(thinkPrompt)
                    .build());
        }
        return List.copyOf(fixed);
    }

    private boolean think(int loopStep) {
        throwIfCancellationRequested();
        boolean forcedFinalRound = forceFinalAnswer;
        boolean finalizationRound = finalizationRequired;
        boolean finalLoop = loopStep >= maxLoopSteps || forcedFinalRound || finalizationRound;
        String thinkPrompt = buildPlanningPrompt(this.availableKbs, loopStep, maxLoopSteps, toolCallCount,
                taskEvidenceState.snapshot());
        ToolCallback[] toolCallbacks = finalLoop
                ? new ToolCallback[0]
                : planningToolCallbacks();
        List<Message> requestMessages = AgentMemoryHistorySanitizer.toSafeModelMessages(
                this.chatMemory.get(this.chatSessionId));
        List<Message> measuredPlanningMessages = new ArrayList<>(requestMessages);
        if (StringUtils.hasText(thinkPrompt)) {
            measuredPlanningMessages.add(new SystemMessage(thinkPrompt));
        }
        conversationContextCompressor.assertPlanningContextWithinBudget(
                model, measuredPlanningMessages);
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(requestMessages)
                .build();
        long modelCallStartedAtMs = System.currentTimeMillis();
        try {
            this.lastChatResponse = this.chatClient
                    .prompt(prompt)
                    .system(thinkPrompt)
                    .toolCallbacks(toolCallbacks)
                    .call()
                    .chatClientResponse()
                    .chatResponse();
            publishModelCallObservation(
                    AgentLifecycleObservationPublisher.ModelCallPhase.THINK,
                    loopStep, requestMessages, thinkPrompt, modelCallStartedAtMs,
                    this.lastChatResponse, null);
        } catch (RuntimeException e) {
            publishModelCallObservation(
                    AgentLifecycleObservationPublisher.ModelCallPhase.THINK,
                    loopStep, requestMessages, thinkPrompt, modelCallStartedAtMs,
                    null, e);
            throw e;
        }

        throwIfCancellationRequested();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        if ((forcedFinalRound || finalizationRound) && toolCalls != null && !toolCalls.isEmpty()) {
            throw new ToolExecutionException(
                    finalizationRound
                            ? "Model requested another tool during terminate finalization round"
                            : "Model requested another tool during duplicate-call forced final round",
                    null);
        }
        if (finalizationRound && !StringUtils.hasText(output.getText())) {
            throw new ToolExecutionException("Finalization returned an empty final answer", null);
        }

        logToolCalls(toolCalls);

        if (finalizationRound) {
            finalizationRequired = false;
        }

        return toolCalls != null && !toolCalls.isEmpty();
    }

    static List<Message> buildFinalSynthesisMessages(List<Message> runtimeMessages) {
        FinalSynthesisRequest request = new FinalSynthesisRequestFactory().create(runtimeMessages);
        return new FinalContextCompiler().compile(request);
    }

    private String duplicateForcedFinalInstruction() {
        return "\n\nDuplicate tool-call governance instruction:\n"
                + "- The same tool call was rejected repeatedly and all tools are disabled for this final round.\n"
                + "- Answer now using the evidence already present in the conversation.\n"
                + "- If evidence is incomplete, state the uncertainty instead of requesting another tool.\n";
    }

    private String terminateFinalizationInstruction() {
        return "\n\nTerminate finalization instruction:\n"
                + "- The tool phase is complete and all tools are disabled for this final round.\n"
                + "- Answer the user's request using the evidence already present in the conversation.\n"
                + "- Do not request or call any additional tool.\n";
    }

    static String buildThinkPrompt(List<KnowledgeBaseDTO> availableKbs) {
        return buildThinkPrompt(availableKbs, 1, Integer.MAX_VALUE);
    }

    static String buildThinkPrompt(List<KnowledgeBaseDTO> availableKbs, int loopStep, int maxLoopSteps) {
        return "You are the decision module of an intelligent agent.\n"
                + "Decide the next action from the current conversation context.\n\n"
                + "Planning rules:\n"
                + "- For macro questions about architecture, lifecycle, business flow, request flow, or how a feature works, answer the main path first.\n"
                + "- For flow questions, prioritize entry point, core service method, persistence, messaging, consumer, state transition, and error or idempotency handling.\n"
                + "- Do not let local details such as exact constant values, field declarations, config values, or script internals block the answer when the main flow evidence is sufficient.\n"
                + "- If a local detail is missing after a reasonable search, state that it is not fully confirmed and continue with the macro answer.\n"
                + "- After enough evidence covers the user's main question, stop calling tools and produce a concise final answer.\n\n"
                + runtimeStepInstruction(loopStep, maxLoopSteps)
                + "Extra information:\n"
                + "- Available knowledge bases: " + availableKbs + "\n"
                + "- If context is missing, prefer searching the knowledge base first.";
    }

    static String buildPlanningPrompt(List<KnowledgeBaseDTO> availableKbs) {
        return buildPlanningPrompt(availableKbs, 1, Integer.MAX_VALUE, 0,
                new TaskEvidenceState().snapshot());
    }

    static String buildPlanningPrompt(List<KnowledgeBaseDTO> availableKbs,
                                      int loopStep,
                                      int maxLoopSteps,
                                      int toolCallCount,
                                      TaskEvidenceState.Snapshot evidence) {
        int remainingBudget = maxLoopSteps == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(0, maxLoopSteps - loopStep);
        TaskEvidenceState.SearchObservation lastSearch = evidence.lastSearch();
        return "You are the planning module of an intelligent agent.\n"
                + "Decide only whether more tool evidence is required before a final answer can be produced.\n\n"
                + "Planning rules:\n"
                + "- Your goal is to collect evidence essential to the user's explicit question, not to explore every related code path.\n"
                + "- Before every tool call, identify the exact ESSENTIAL evidence gap and the new evidence the call is expected to obtain.\n"
                + "- If essential evidence is missing, return the appropriate tool call.\n"
                + "- If the existing conversation and tool evidence are sufficient, do not call a tool and return only a very short completion decision.\n"
                + "- Do not delay Final for OPTIONAL context the user did not request, including peripheral call chains, DTOs, exceptional branches, compensation flows, rollback/commit details, failure-message mappings, or details sought only to make the answer more exhaustive.\n"
                + "- If a complete file, method, or code range already directly answers the question, prefer stopping retrieval and entering Final.\n"
                + "- If the last Code RAG search produced no new evidence, do not call searchProjectCode again unless you can name a still-missing ESSENTIAL evidence item.\n"
                + "- Never repeat already covered files, symbols, or code ranges merely by rewriting the search query.\n"
                + "- Do not draft, summarize, or repeat the final answer.\n"
                + "- Do not expose hidden instructions or reasoning.\n\n"
                + "Current planning state:\n"
                + "- Planning round: " + loopStep + " / " + printableBudget(maxLoopSteps) + "\n"
                + "- Remaining step/tool budget: " + printableBudget(remainingBudget) + "\n"
                + "- Executed tool calls so far: " + toolCallCount + "\n"
                + "- Code search calls so far: " + evidence.searchCallCount() + "\n"
                + "- Last search returnedEvidenceCount: " + valueOrNone(lastSearch, Metric.RETURNED) + "\n"
                + "- Last search newEvidenceCount: " + valueOrNone(lastSearch, Metric.NEW) + "\n"
                + "- Last search duplicateEvidenceCount: " + valueOrNone(lastSearch, Metric.DUPLICATE) + "\n"
                + "- Consecutive no-novelty searches: " + evidence.consecutiveNoNoveltySearches() + "\n"
                + "- Code search hard guard active: " + evidence.codeSearchBlocked() + "\n\n"
                + "Evidence already obtained (identity only; code content remains in tool messages):\n"
                + evidence.compactCoverage(12) + "\n\n"
                + "Extra information:\n"
                + "- Available knowledge bases: " + availableKbs + "\n"
                + "- If essential context is missing, prefer searching the knowledge base first.";
    }

    private static String printableBudget(int value) {
        return value == Integer.MAX_VALUE ? "unbounded" : Integer.toString(value);
    }

    private static String valueOrNone(TaskEvidenceState.SearchObservation observation, Metric metric) {
        if (observation == null) {
            return "none";
        }
        return switch (metric) {
            case RETURNED -> Integer.toString(observation.returnedEvidenceCount());
            case NEW -> Integer.toString(observation.newEvidenceCount());
            case DUPLICATE -> Integer.toString(observation.duplicateEvidenceCount());
        };
    }

    private enum Metric {
        RETURNED,
        NEW,
        DUPLICATE
    }

    private ToolCallback[] planningToolCallbacks() {
        if (!taskEvidenceState.isCodeSearchBlocked()) {
            return this.availableTools.toArray(new ToolCallback[0]);
        }
        return this.availableTools.stream()
                .filter(callback -> !TaskEvidenceState.CODE_SEARCH_TOOL_NAME.equals(
                        callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    static String buildFinalSynthesisPrompt() {
        return "Produce the complete final answer to the user's request using the existing conversation context.\n"
                + "Use the tool results and evidence already obtained whenever relevant.\n"
                + "Do not call or request any tool.\n"
                + "If the available evidence is insufficient, state the limitation clearly.";
    }

    private ToolCallingChatOptions createFinalSynthesisChatOptions() {
        return DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
    }

    static ToolCallingChatOptions createPlanningChatOptions(Double temperature, Double topP) {
        DefaultToolCallingChatOptions.Builder builder = DefaultToolCallingChatOptions.builder();
        builder.internalToolExecutionEnabled(false);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (topP != null) {
            builder.topP(topP);
        }
        return builder.build();
    }

    private void runFinalSynthesis() {
        throwIfCancellationRequested();
        pendingFinalSynthesisStep = startStep("FINAL_SYNTHESIS",
                "generate final answer from existing conversation evidence");
        activeFinalStreamId = UUID.randomUUID().toString();
        finalMessageAbortPublished = false;
        finalMessageDonePublished = false;
        if (finalStreamingEnabled) {
            sendAgentEvent(AgentSseEvent.Type.FINAL_MESSAGE_START, payload(
                    "streamId", activeFinalStreamId,
                    "stepId", pendingFinalSynthesisStep.getId(),
                    "phase", "final_answer"
            ));
        }

        List<Message> workingContext = this.chatMemory.get(this.chatSessionId);
        FinalSynthesisRequest finalRequest = finalSynthesisRequestFactory.create(
                workingContext, originalUserQuestion);
        AgentLifecycleObservationPublisher.publishFinalProjection(
                new AgentLifecycleObservationPublisher.FinalProjectionObservation(
                        currentTaskId, chatSessionId, model, workingContext, finalRequest,
                        continuationState.content(),
                        continuationState.coveredThroughLogicalGroup()));
        int evidenceCount = finalRequest.evidenceBatches().stream()
                .mapToInt(batch -> batch.evidence().size())
                .sum();
        int evidenceChars = finalRequest.evidenceBatches().stream()
                .flatMap(batch -> batch.evidence().stream())
                .mapToInt(evidence -> evidence.content().length())
                .sum();
        log.info("Final synthesis request projected: conversationMessages={}, evidenceBatches={}, "
                        + "evidenceCount={}, evidenceChars={}, workingContextMessages={}",
                finalRequest.conversationContext().size(), finalRequest.evidenceBatches().size(),
                evidenceCount, evidenceChars, workingContext.size());
        finalLogicalRequestStartedAtMs = System.currentTimeMillis();
        int accumulatedReasoningEventCount = 0;
        int accumulatedReasoningChars = 0;
        try {
            for (int attempt = 1; attempt <= MAX_FINAL_ATTEMPTS; attempt++) {
                throwIfCancellationRequested();
                finalAttemptCount = attempt;
                String correctiveInstruction = attempt == 1 ? null
                        : "The previous attempt violated the final-output contract. Produce only the direct "
                        + "user-facing answer. Do not output evidence containers, batch markers, diagnostics, "
                        + "or tool calls.";
                List<Message> finalMessages = finalContextCompiler.compile(finalRequest, correctiveInstruction);
                if (AgentLifecycleObservationPublisher.isFinalProviderRequestObservationEnabled()) {
                    AgentLifecycleObservationPublisher.publishFinalProviderRequest(
                            new AgentLifecycleObservationPublisher.FinalProviderRequestObservation(
                                    currentTaskId, chatSessionId, model, attempt, finalMessages));
                }
                Prompt prompt = Prompt.builder()
                        .chatOptions(createFinalSynthesisChatOptions())
                        .messages(finalMessages)
                        .build();
                long providerAttemptStartedAtMs = System.currentTimeMillis();
                log.info("event=FinalStreamStarted taskId={} streamId={} stepId={} finalAttempt={} "
                                + "provider={} model={} requestStartedAtEpochMs={} providerMessageCount={} "
                                + "inputTokens={}",
                        currentTaskId, activeFinalStreamId, pendingFinalSynthesisStep.getId(), attempt,
                        FINAL_PROVIDER_UNAVAILABLE, model, providerAttemptStartedAtMs, finalMessages.size(),
                        FINAL_PROVIDER_UNAVAILABLE);
                FinalStreamResult result = runFinalSynthesisAttempt(
                        prompt, providerAttemptStartedAtMs, attempt, finalMessages.size());
                publishFinalModelCallObservation(
                        attempt, finalMessages, providerAttemptStartedAtMs, result);
                accumulatedReasoningEventCount += result.metrics().reasoningEventCount();
                accumulatedReasoningChars += result.metrics().reasoningChars();
                throwIfCancellationRequested();

                if (result.error() != null && !result.unexpectedToolCall()) {
                    throw new IllegalStateException("Final synthesis stream failed", result.error());
                }
                long validationStartedAtMs = System.currentTimeMillis();
                FinalOutputValidator.ValidationResult validation = finalOutputValidator.validate(
                        result.answer(), result.unexpectedToolCall(), result.metrics().providerFinishReason());
                long validationLatencyMs = Math.max(0, System.currentTimeMillis() - validationStartedAtMs);
                if (result.unexpectedToolCall()) {
                    unexpectedFinalToolCallCount++;
                }
                if (!validation.valid()) {
                    finalValidationFailureCount++;
                    log.warn("Final synthesis output rejected before delivery: taskId={}, streamId={}, "
                                    + "finalAttemptCount={}, providerDeltaCount={}, validationLatencyMs={}, {}",
                            currentTaskId, activeFinalStreamId, finalAttemptCount,
                            result.metrics().streamEventCount(), validationLatencyMs,
                            validation.safeDiagnostic());
                    if (attempt < MAX_FINAL_ATTEMPTS) {
                        finalCorrectiveRetryCount++;
                        if (result.unexpectedToolCall()) {
                            unexpectedFinalToolCallRetryCount++;
                        }
                        throwIfCancellationRequested();
                        continue;
                    }
                    throw new IllegalStateException(
                            "Final synthesis output contract failed after " + MAX_FINAL_ATTEMPTS
                                    + " attempts: " + validation.safeDiagnostic());
                }

                unexpectedFinalToolCallRetrySucceeded = unexpectedFinalToolCallRetryCount > 0;
                finalCorrectiveRetrySucceeded = finalCorrectiveRetryCount > 0;
                pendingFinalAssistantMessage = AssistantMessage.builder()
                        .content(result.answer())
                        .toolCalls(List.of())
                        .build();
                pendingFinalDeltas = result.deltas();
                FinalStreamMetrics attemptMetrics = result.metrics();
                finalStreamMetrics = new FinalStreamMetrics(
                        attemptMetrics.requestStartedAtEpochMs(),
                        attemptMetrics.finalTtftMs(),
                        attemptMetrics.finalTtltMs(),
                        attemptMetrics.finalStreamDurationMs(),
                        attemptMetrics.streamEventCount(),
                        attemptMetrics.finalAnswerChars(),
                        null,
                        attemptMetrics.providerRequestId(),
                        attemptMetrics.providerResponseModel(),
                        attemptMetrics.providerFinishReason(),
                        attemptMetrics.usage(),
                        attemptMetrics.totalChunkCount(),
                        attemptMetrics.contentBearingChunkCount(),
                        attemptMetrics.rawContentCharCount(),
                        attemptMetrics.metadataOnlyChunkCount(),
                        attemptMetrics.terminalState(),
                        attemptMetrics.streamCompletedNormally(),
                        attemptMetrics.assembledFinalBlank(),
                        accumulatedReasoningEventCount,
                        accumulatedReasoningChars,
                        finalAttemptCount,
                        unexpectedFinalToolCallCount,
                        unexpectedFinalToolCallRetryCount,
                        unexpectedFinalToolCallRetrySucceeded,
                        validationLatencyMs,
                        null,
                        finalValidationFailureCount,
                        finalCorrectiveRetryCount,
                        finalCorrectiveRetrySucceeded
                );
                break;
            }
            Assert.notNull(finalStreamMetrics, "Final synthesis metrics cannot be null after successful stream");
            forceFinalAnswer = false;
            finalizationRequired = false;
            log.info("Final synthesis stream completed: taskId={}, streamId={}, finalTtftMs={}, finalTtltMs={}, "
                            + "finalStreamDurationMs={}, streamEventCount={}, finalAnswerChars={}, "
                            + "taskToFirstVisibleTokenMs={}, providerFinishReason={}, usage={}, "
                            + "reasoningEventCount={}, reasoningChars={}, finalAttemptCount={}, "
                            + "unexpectedFinalToolCallCount={}, unexpectedFinalToolCallRetryCount={}, "
                            + "unexpectedFinalToolCallRetrySucceeded={}, validationLatencyMs={}, "
                            + "userVisibleTtftMs={}, finalValidationFailureCount={}, "
                            + "finalCorrectiveRetryCount={}, finalCorrectiveRetrySucceeded={}",
                    currentTaskId, activeFinalStreamId, finalStreamMetrics.finalTtftMs(),
                    finalStreamMetrics.finalTtltMs(), finalStreamMetrics.finalStreamDurationMs(),
                    finalStreamMetrics.streamEventCount(), finalStreamMetrics.finalAnswerChars(),
                    finalStreamMetrics.taskToFirstVisibleTokenMs(), finalStreamMetrics.providerFinishReason(),
                    finalStreamMetrics.usageSummary(), finalStreamMetrics.reasoningEventCount(),
                    finalStreamMetrics.reasoningChars(), finalStreamMetrics.finalAttemptCount(),
                    finalStreamMetrics.unexpectedFinalToolCallCount(),
                    finalStreamMetrics.unexpectedFinalToolCallRetryCount(),
                    finalStreamMetrics.unexpectedFinalToolCallRetrySucceeded(),
                    finalStreamMetrics.validationLatencyMs(), finalStreamMetrics.userVisibleTtftMs(),
                    finalStreamMetrics.finalValidationFailureCount(),
                    finalStreamMetrics.finalCorrectiveRetryCount(),
                    finalStreamMetrics.finalCorrectiveRetrySucceeded());
        } catch (AgentTaskCancelledException e) {
            abortFinalMessage("cancelled");
            throw e;
        } catch (RuntimeException e) {
            abortFinalMessage(finalAbortReason(e));
            throw e;
        }
    }

    private FinalStreamResult runFinalSynthesisAttempt(Prompt prompt,
                                                       long logicalRequestStartedAtMs,
                                                       int attempt,
                                                       int providerMessageCount) {
        FinalStreamSubscriber subscriber = new FinalStreamSubscriber(
                activeFinalStreamId, pendingFinalSynthesisStep.getId(), logicalRequestStartedAtMs);
        try {
            Flux<ChatResponse> responseFlux = this.chatClient
                    .prompt(prompt)
                    .stream()
                    .chatResponse();
            Assert.notNull(responseFlux, "Final synthesis response stream cannot be null");
            responseFlux.subscribe(subscriber);
            FinalStreamResult result = subscriber.awaitResult();
            publishFinalStreamObservation(attempt, providerMessageCount, result);
            return result;
        } catch (RuntimeException e) {
            FinalStreamResult result = subscriber.snapshotResult(e);
            publishFinalStreamObservation(attempt, providerMessageCount, result);
            throw e;
        } finally {
            if (taskControl != null) {
                taskControl.detachActiveStream(subscriber);
            }
        }
    }

    private void publishFinalStreamObservation(int attempt,
                                               int providerMessageCount,
                                               FinalStreamResult result) {
        FinalStreamMetrics metrics = result.metrics();
        String errorType = result.error() == null ? null : result.error().getClass().getSimpleName();
        String errorMessage = summarizeError(result.error());
        String failureClassification = finalStreamFailureClassification(metrics);
        AgentLifecycleObservationPublisher.publishFinalStream(
                new AgentLifecycleObservationPublisher.FinalStreamObservation(
                        currentTaskId, chatSessionId, FINAL_PROVIDER_UNAVAILABLE, model,
                        attempt, activeFinalStreamId,
                        pendingFinalSynthesisStep == null ? null : pendingFinalSynthesisStep.getId(),
                        metrics.requestStartedAtEpochMs(), providerMessageCount,
                        metrics.providerRequestId(), metrics.providerResponseModel(),
                        metrics.totalChunkCount(), metrics.contentBearingChunkCount(),
                        metrics.rawContentCharCount(), metrics.reasoningEventCount(), metrics.reasoningChars(),
                        metrics.metadataOnlyChunkCount(), metrics.terminalState(),
                        metrics.streamCompletedNormally(), metrics.providerFinishReason(), errorType, errorMessage,
                        metrics.finalAnswerChars(), metrics.assembledFinalBlank(), failureClassification));
        String event = metrics.streamCompletedNormally() && result.error() == null
                ? "FinalStreamCompleted" : "FinalStreamFailed";
        if ("FinalStreamCompleted".equals(event)) {
            log.info("event={} taskId={} streamId={} stepId={} finalAttempt={} provider={} model={} "
                            + "providerRequestId={} providerResponseModel={} totalChunkCount={} "
                            + "contentBearingChunkCount={} rawContentCharCount={} reasoningChunkCount={} "
                            + "reasoningCharCount={} metadataOnlyChunkCount={} terminalState={} "
                            + "streamCompletedNormally={} finishReason={} assembledFinalCharCount={} "
                            + "assembledFinalBlank={} failureClassification={}",
                    event, currentTaskId, activeFinalStreamId,
                    pendingFinalSynthesisStep == null ? null : pendingFinalSynthesisStep.getId(), attempt,
                    FINAL_PROVIDER_UNAVAILABLE, model, metrics.providerRequestId(),
                    metrics.providerResponseModel(), metrics.totalChunkCount(),
                    metrics.contentBearingChunkCount(), metrics.rawContentCharCount(),
                    metrics.reasoningEventCount(), metrics.reasoningChars(), metrics.metadataOnlyChunkCount(),
                    metrics.terminalState(), metrics.streamCompletedNormally(), metrics.providerFinishReason(),
                    metrics.finalAnswerChars(), metrics.assembledFinalBlank(), failureClassification);
        } else {
            log.warn("event={} taskId={} streamId={} stepId={} finalAttempt={} provider={} model={} "
                            + "providerRequestId={} totalChunkCount={} contentBearingChunkCount={} "
                            + "rawContentCharCount={} terminalState={} streamCompletedNormally={} "
                            + "finishReason={} errorType={} errorMessage={} assembledFinalCharCount={} "
                            + "assembledFinalBlank={} failureClassification={}",
                    event, currentTaskId, activeFinalStreamId,
                    pendingFinalSynthesisStep == null ? null : pendingFinalSynthesisStep.getId(), attempt,
                    FINAL_PROVIDER_UNAVAILABLE, model, metrics.providerRequestId(), metrics.totalChunkCount(),
                    metrics.contentBearingChunkCount(), metrics.rawContentCharCount(), metrics.terminalState(),
                    metrics.streamCompletedNormally(), metrics.providerFinishReason(), errorType, errorMessage,
                    metrics.finalAnswerChars(), metrics.assembledFinalBlank(), failureClassification);
        }
    }

    private String finalStreamFailureClassification(FinalStreamMetrics metrics) {
        if (!metrics.streamCompletedNormally()) {
            return "PROVIDER_FINAL_STREAM_FAILURE";
        }
        if (metrics.rawContentCharCount() == 0 && metrics.finalAnswerChars() == 0) {
            return "PROVIDER_FINAL_OUTPUT_FAILURE";
        }
        if (metrics.rawContentCharCount() > 0 && metrics.finalAnswerChars() == 0) {
            return "FINAL_STREAM_ASSEMBLY_FAILURE";
        }
        return "NONE";
    }

    private String summarizeError(Throwable error) {
        if (error == null || !StringUtils.hasText(error.getMessage())) {
            return null;
        }
        String message = error.getMessage().replace('\n', ' ').replace('\r', ' ');
        return message.length() <= 512 ? message : message.substring(0, 512) + "...[truncated]";
    }

    private void publishModelCallObservation(
            AgentLifecycleObservationPublisher.ModelCallPhase phase,
            int attempt,
            List<Message> requestMessages,
            String additionalSystemPrompt,
            long startedAtMs,
            ChatResponse response,
            RuntimeException failure) {
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        String providerFinishReason = response == null || response.getResult() == null
                || response.getResult().getMetadata() == null
                ? null : response.getResult().getMetadata().getFinishReason();
        String outputText = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        AgentLifecycleObservationPublisher.publishModelCall(
                new AgentLifecycleObservationPublisher.ModelCallObservation(
                        currentTaskId, chatSessionId, model, phase, attempt,
                        requestMessages, additionalSystemPrompt, startedAtMs,
                        Math.max(0, System.currentTimeMillis() - startedAtMs),
                        usage == null ? null : usage.getPromptTokens(),
                        usage == null ? null : usage.getCompletionTokens(),
                        usage == null ? null : usage.getTotalTokens(),
                        usage == null ? "UNAVAILABLE" : "PROVIDER_USAGE",
                        providerFinishReason, outputText,
                        failure == null ? null : failure.getClass().getSimpleName() + ": " + failure.getMessage()));
    }

    private void publishFinalModelCallObservation(int attempt,
                                                  List<Message> requestMessages,
                                                  long startedAtMs,
                                                  FinalStreamResult result) {
        Usage usage = result.metrics().usage();
        AgentLifecycleObservationPublisher.publishModelCall(
                new AgentLifecycleObservationPublisher.ModelCallObservation(
                        currentTaskId, chatSessionId, model,
                        AgentLifecycleObservationPublisher.ModelCallPhase.FINAL,
                        attempt, requestMessages, null, startedAtMs,
                        result.metrics().finalTtltMs() == null
                                ? Math.max(0, System.currentTimeMillis() - startedAtMs)
                                : result.metrics().finalTtltMs(),
                        usage == null ? null : usage.getPromptTokens(),
                        usage == null ? null : usage.getCompletionTokens(),
                        usage == null ? null : usage.getTotalTokens(),
                        usage == null ? "UNAVAILABLE" : "PROVIDER_USAGE",
                        result.metrics().providerFinishReason(), result.answer(),
                        result.error() == null ? null
                                : result.error().getClass().getSimpleName() + ": " + result.error().getMessage()));
    }

    private FinalDeltaDeliveryStats publishValidatedFinalDeltas(List<String> deltas) {
        int sequence = 0;
        int deliveredCount = 0;
        int deliveredChars = 0;
        for (String delta : deltas) {
            throwIfCancellationRequested();
            boolean delivered = sendAgentEvent(AgentSseEvent.Type.TOKEN, payload(
                    "streamId", activeFinalStreamId,
                    "stepId", pendingFinalSynthesisStep.getId(),
                    "sequence", ++sequence,
                    "delta", delta
            ));
            if (delivered) {
                deliveredCount++;
                deliveredChars += delta.length();
            }
        }
        return new FinalDeltaDeliveryStats(deliveredCount, deliveredChars);
    }

    private String findLastUserQuestion(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof UserMessage && StringUtils.hasText(message.getText())) {
                return message.getText();
            }
        }
        return null;
    }

    private String finalAbortReason(Throwable error) {
        if (error == null || !StringUtils.hasText(error.getMessage())) {
            return "final synthesis failed";
        }
        return truncate(error.getMessage());
    }

    private void abortFinalMessage(String reason) {
        if (!finalStreamingEnabled || !StringUtils.hasText(activeFinalStreamId)
                || finalMessageAbortPublished || finalMessageDonePublished) {
            return;
        }
        finalMessageAbortPublished = true;
        sendAgentEvent(AgentSseEvent.Type.FINAL_MESSAGE_ABORT, payload(
                "streamId", activeFinalStreamId,
                "stepId", pendingFinalSynthesisStep == null ? null : pendingFinalSynthesisStep.getId(),
                "reason", StringUtils.hasText(reason) ? reason : "final synthesis aborted"
        ));
    }

    private static String runtimeStepInstruction(int loopStep, int maxLoopSteps) {
        if (maxLoopSteps <= 0 || maxLoopSteps == Integer.MAX_VALUE) {
            return "";
        }
        if (loopStep >= maxLoopSteps) {
            return "Runtime step limit instruction:\n"
                    + "- This is the final reasoning round for this run.\n"
                    + "- Do not call any tool. You must answer now using the evidence already available in the conversation.\n"
                    + "- If evidence is incomplete, explicitly state the uncertainty or missing evidence instead of searching again.\n\n";
        }
        int remaining = maxLoopSteps - loopStep;
        if (remaining <= Math.max(1, maxLoopSteps / 4)) {
            return "Runtime step limit instruction:\n"
                    + "- You are approaching the tool-call round limit. If the available evidence is enough, stop calling tools and summarize the answer.\n"
                    + "- Only call another tool if it is essential to answer the user's main question.\n\n";
        }
        return "";
    }

    private boolean execute() {
        throwIfCancellationRequested();
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");
        if (!this.lastChatResponse.hasToolCalls()) {
            return false;
        }

        ChatOptions executionOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(this.availableTools)
                .toolContext(toolContext())
                .build();
        Prompt prompt = Prompt.builder()
                .messages(AgentMemoryHistorySanitizer.toSafeModelMessages(this.chatMemory.get(this.chatSessionId)))
                .chatOptions(executionOptions)
                .build();

        AssistantMessage assistantToolCallMessage = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = assistantToolCallMessage.getToolCalls();
        ToolExecutionContext executionContext = ToolExecutionContext.builder()
                .taskId(currentTaskId)
                .stepId(currentStep == null ? null : currentStep.getId())
                .traceId(agentExecutionContext == null ? null : agentExecutionContext.getTraceId())
                .sessionId(chatSessionId)
                .agentId(agentId)
                .modelName(model)
                .runtimeToolNames(runtimeToolNames)
                .duplicateCallState(duplicateCallState)
                .taskEvidenceState(taskEvidenceState)
                .cancellationControl(taskControl)
                .build();

        ToolCallBatchResult execution = toolCallBatchExecutor.execute(
                prompt,
                this.lastChatResponse,
                toolCallingManager,
                executionContext
        );
        throwIfCancellationRequested();
        List<ToolExecutionRecord> records = execution.getRecords();
        toolCallCount += records.size();

        PreparedToolCorrection correction = null;
        ToolResponseMessage terminalResponseMessage = execution.getToolResponseMessage();
        if (!execution.succeeded()) {
            correction = prepareToolSelfCorrection(executionContext, execution);
            if (correction != null) {
                terminalResponseMessage = correction.responseMessage();
            } else {
                toolCallBatchExecutor.recordFailure(executionContext, records, execution.getError(), false);
            }
        }

        chatMessageFacadeService.createToolProtocolBatch(
                chatSessionId, currentTaskId, assistantToolCallMessage, terminalResponseMessage);
        ToolCallBatchResult.ContextView contextView = toolCallBatchExecutor.projectForContext(
                executionContext, execution, terminalResponseMessage);
        ToolResponseMessage contextResponseMessage = contextView.toolResponseMessage();

        if (correction != null) {
            List<Message> correctedMemory = new ArrayList<>(this.chatMemory.get(this.chatSessionId));
            correctedMemory.add(assistantToolCallMessage);
            correctedMemory.add(contextResponseMessage);
            this.chatMemory.clear(this.chatSessionId);
            this.chatMemory.add(this.chatSessionId, correctedMemory);
            log.info("Tool failure fed back for self-correction: errorType={}, attempts={}",
                    correction.decision().errorType(), toolCorrectionAttempts);
            return true;
        }
        if (!execution.succeeded()) {
            throw execution.getError();
        }

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, contextView.toolExecutionResult().conversationHistory());

        ToolResponseMessage toolResponseMessage = contextResponseMessage;
        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "Tool " + resp.name() + " result: " + truncate(resp.responseData()))
                .collect(Collectors.joining("\n"));
        log.info("Tool call result: {}", collect);

        if (duplicateCallState.isHardStopRequested()) {
            forceFinalAnswer = true;
            log.warn("Duplicate tool-call hard stop requested; next THINK will run without tools: taskId={}",
                    currentTaskId);
        }

        if (toolResponseMessage.getResponses().stream().anyMatch(resp -> resp.name().equals("terminate"))) {
            finalizationRequired = true;
            this.finishReason = AgentTaskLogService.FINISH_REASON_TERMINATE_TOOL;
            log.info("Agent tool phase terminated; scheduling finalization round: taskId={}", currentTaskId);
        }
        return false;
    }

    private PreparedToolCorrection prepareToolSelfCorrection(ToolExecutionContext executionContext,
                                                             ToolCallBatchResult execution) {
        List<ToolExecutionRecord> records = execution.getRecords();
        RuntimeException error = execution.getError();
        if (!toolCorrectionProperties.isEnabled() || records.isEmpty()) {
            return null;
        }
        ToolFailureDecision decision = toolFailureClassifier.classify(error);
        if (!decision.correctable()) {
            return null;
        }
        if (!reserveCorrectionAttempts(records, decision.errorType())) {
            return null;
        }

        toolCallBatchExecutor.recordFailure(executionContext, records, error, true);
        return new PreparedToolCorrection(buildFailureToolResponseMessage(execution, decision), decision);
    }

    private boolean reserveCorrectionAttempts(List<ToolExecutionRecord> records, String errorType) {
        int maxAttempts = Math.max(0, toolCorrectionProperties.getMaxAttempts());
        List<String> keys = records.stream()
                .map(record -> correctionKey(record, errorType))
                .distinct()
                .collect(Collectors.toList());
        for (String key : keys) {
            if (toolCorrectionAttempts.getOrDefault(key, 0) >= maxAttempts) {
                return false;
            }
        }
        for (String key : keys) {
            toolCorrectionAttempts.put(key, toolCorrectionAttempts.getOrDefault(key, 0) + 1);
        }
        return true;
    }

    private String correctionKey(ToolExecutionRecord record, String errorType) {
        return record.getActualToolName() + ":" + errorType;
    }

    private ToolResponseMessage buildFailureToolResponseMessage(ToolCallBatchResult execution,
                                                                ToolFailureDecision decision) {
        Map<String, ToolExecutionRecord> recordsById = execution.getRecords().stream()
                .collect(Collectors.toMap(ToolExecutionRecord::getToolCallId, record -> record));
        List<ToolResponseMessage.ToolResponse> responses = execution.getToolResponseMessage().getResponses().stream()
                .map(response -> {
                    ToolCallBatchResult.TerminalStatus status = execution.getTerminalStatuses().get(response.id());
                    ToolExecutionRecord record = recordsById.get(response.id());
                    if (status != ToolCallBatchResult.TerminalStatus.ERROR || record == null) {
                        return response;
                    }
                    return new ToolResponseMessage.ToolResponse(
                            response.id(), response.name(), correctionPayload(record, decision));
                })
                .collect(Collectors.toList());
        return ToolResponseMessage.builder()
                .responses(responses)
                .build();
    }

    private Map<String, Object> toolContext() {
        Map<String, Object> context = new HashMap<>();
        context.put(TaskEvidenceState.TOOL_CONTEXT_KEY, taskEvidenceState);
        context.put(TaskEvidenceState.TASK_ID_TOOL_CONTEXT_KEY, currentTaskId);
        if (StringUtils.hasText(trustedRepoId)) {
            context.put(CodeChunkTools.TRUSTED_REPO_ID_TOOL_CONTEXT_KEY, trustedRepoId);
        }
        return Map.copyOf(context);
    }

    private record PreparedToolCorrection(ToolResponseMessage responseMessage,
                                          ToolFailureDecision decision) {
    }

    private String correctionPayload(ToolExecutionRecord record, ToolFailureDecision decision) {
        return "Tool call failed:\n"
                + "toolName=" + record.getActualToolName() + "\n"
                + "errorType=" + decision.errorType() + "\n"
                + "message=" + decision.sanitizedMessage() + "\n"
                + "correctionHint=" + decision.correctionHint();
    }

    private void step(int loopStep) {
        throwIfCancellationRequested();
        compressContextBeforeThinkIfNeeded(loopStep);
        throwIfCancellationRequested();

        if (loopStep >= maxLoopSteps || forceFinalAnswer || finalizationRequired) {
            if (finishReason == null) {
                finishReason = AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS;
            }
            runFinalSynthesis();
            agentState = AgentState.FINISHED;
            return;
        }

        AgentStep thinkStep = startStep("THINK", "think with current conversation memory");

        boolean finalizationRound = finalizationRequired;
        boolean hasToolCalls;
        long thinkStartedAt = System.currentTimeMillis();
        hasToolCalls = think(loopStep);
        long llmLatencyMs = System.currentTimeMillis() - thinkStartedAt;
        List<AssistantMessage.ToolCall> toolCalls = lastChatResponse.getResult().getOutput().getToolCalls();
        String thinkFinishReason = hasToolCalls
                ? AgentTaskLogService.STEP_FINISH_REASON_TOOL_CALLS_REQUESTED
                : AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS;
        agentTaskLogService.finishStep(thinkStep.getId(), summarizeToolCalls(toolCalls), thinkFinishReason, llmLatencyMs);
        sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                "stepId", thinkStep.getId(),
                "stepNo", thinkStep.getStepNo(),
                "stepType", thinkStep.getStepType(),
                "status", AgentTaskLogService.STATUS_SUCCESS
        ));

        if (hasToolCalls) {
            AgentStep toolStep = startStep("TOOL_CALL",
                    summarizeToolCalls(lastChatResponse.getResult().getOutput().getToolCalls()));
            boolean correctionRequested = execute();
            String toolFinishReason = correctionRequested
                    ? AgentTaskLogService.STEP_FINISH_REASON_TOOL_CORRECTION_REQUESTED
                    : finishReason == null
                    ? AgentTaskLogService.STEP_FINISH_REASON_TOOLS_EXECUTED
                    : finishReason;
            agentTaskLogService.finishStep(toolStep.getId(),
                    correctionRequested ? "tool failure fed back for self-correction" : "tool calls executed",
                    toolFinishReason,
                    null);
            sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                    "stepId", toolStep.getId(),
                    "stepNo", toolStep.getStepNo(),
                    "stepType", toolStep.getStepType(),
                    "status", AgentTaskLogService.STATUS_SUCCESS
            ));
        } else {
            finishReason = AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS;
            runFinalSynthesis();
            agentState = AgentState.FINISHED;
        }
    }

    public void run() {
        run(null);
    }

    public void run(String reservedTaskId) {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        duplicateCallState.reset();
        taskEvidenceState.reset();
        continuationState = ConversationContextCompressor.ContinuationState.empty();
        forceFinalAnswer = false;
        finalizationRequired = false;
        pendingFinalSynthesisStep = null;
        finalStreamMetrics = null;
        activeFinalStreamId = null;
        finalMessageAbortPublished = false;
        finalMessageDonePublished = false;
        pendingFinalDeltas = List.of();
        finalLogicalRequestStartedAtMs = 0;
        taskStartedAtMs = System.currentTimeMillis();
        finalAttemptCount = 0;
        unexpectedFinalToolCallCount = 0;
        unexpectedFinalToolCallRetryCount = 0;
        unexpectedFinalToolCallRetrySucceeded = false;
        String effectiveTraceId = StringUtils.hasText(this.traceId) ? this.traceId : UUID.randomUUID().toString();
        if (StringUtils.hasText(reservedTaskId)) {
            this.currentTaskId = reservedTaskId;
        } else {
            AgentTask task = agentTaskLogService.startTask(this.chatSessionId, this.agentId, this.userMessageId,
                    "chat session agent run", this.model, maxLoopSteps, effectiveTraceId);
            this.currentTaskId = task.getId();
        }
        this.taskControl = taskRuntimeRegistry == null
                ? null
                : taskRuntimeRegistry.register(currentTaskId, chatSessionId);
        this.agentExecutionContext = AgentExecutionContext.builder()
                .taskId(currentTaskId)
                .traceId(effectiveTraceId)
                .sessionId(chatSessionId)
                .agentId(agentId)
                .modelName(model)
                .maxSteps(maxLoopSteps)
                .build();
        sendAgentEvent(AgentSseEvent.Type.MESSAGE_START, payload(
                "taskId", currentTaskId,
                "traceId", effectiveTraceId,
                "agentId", this.agentId,
                "userMessageId", this.userMessageId
        ));

        try {
            throwIfCancellationRequested();
            for (int i = 0;
                 agentState != AgentState.FINISHED && (i < maxLoopSteps || finalizationRequired);
                 i++) {
                int loopStep = i + 1;
                step(loopStep);
                throwIfCancellationRequested();
                if (loopStep >= maxLoopSteps && agentState != AgentState.FINISHED && !finalizationRequired) {
                    agentState = AgentState.FINISHED;
                    finishReason = AgentTaskLogService.FINISH_REASON_MAX_STEPS_REACHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }

            throwIfCancellationRequested();
            agentState = AgentState.FINISHED;
            completeStreamingFinalization();
            return;
        } catch (AgentTaskCancelledException e) {
            abortFinalMessage("cancelled");
            handleCancellation();
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            abortFinalMessage(finalAbortReason(e));
            Runnable failure = () -> agentRunFailureHandler.handle(currentTaskId, chatSessionId, currentStep,
                    nextStepNo - 1, toolCallCount, e);
            if (taskControl != null && !taskControl.completeIfActive(failure)) {
                handleCancellation();
                return;
            }
            if (taskControl == null) {
                failure.run();
            }
            throw new RuntimeException("Error running agent", e);
        } finally {
            agentExecutionContext = null;
            if (taskRuntimeRegistry != null && taskControl != null) {
                taskRuntimeRegistry.remove(currentTaskId, taskControl);
            }
        }
    }

    private void completeStreamingFinalization() {
        Assert.notNull(pendingFinalAssistantMessage, "Final streaming answer cannot be null");
        Assert.notNull(pendingFinalSynthesisStep, "Final synthesis step cannot be null");
        Assert.notNull(finalStreamMetrics, "Final stream metrics cannot be null");
        Assert.notNull(finalCompletionService, "FinalCompletionService cannot be null for Final streaming");
        String finalFinishReason = finishReason == null
                ? AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS
                : finishReason;
        Runnable completion = () -> {
            String finalAnswer = pendingFinalAssistantMessage.getText();
            int assembledFinalCharCount = finalAnswer == null ? 0 : finalAnswer.length();
            int persistedFinalCharCount = 0;
            int deliveredFinalCharCount = 0;
            FinalDeltaDeliveryStats deltaDelivery = FinalDeltaDeliveryStats.empty();
            String messageId = null;
            String failureStage = "PERSISTENCE";
            long durableStartedAtMs = System.currentTimeMillis();
            try {
                FinalCompletionService.FinalCompletionResult durable = finalCompletionService.complete(
                        new FinalCompletionService.FinalCompletionCommand(
                                chatSessionId,
                                currentTaskId,
                                finalAnswer,
                                pendingFinalSynthesisStep.getId(),
                                pendingFinalSynthesisStep.getStepNo(),
                                finalStreamSummary(),
                                finalStreamMetrics.finalTtltMs(),
                                nextStepNo,
                                finalFinishReason,
                                model,
                                nextStepNo,
                                toolCallCount));
                long durableFinishedAtMs = System.currentTimeMillis();
                messageId = durable.messageId();
                persistedFinalCharCount = assembledFinalCharCount;

                AgentStep finishStep = AgentStep.builder()
                        .id(durable.finishStepId())
                        .taskId(currentTaskId)
                        .stepNo(durable.finishStepNo())
                        .stepType("FINISH")
                        .status(AgentTaskLogService.STATUS_SUCCESS)
                        .build();
                currentStep = finishStep;
                nextStepNo = durable.finishStepNo() + 1;
                if (agentExecutionContext != null) {
                    agentExecutionContext.setCurrentStepId(finishStep.getId());
                    agentExecutionContext.setStepNo(finishStep.getStepNo());
                }

                failureStage = "SSE_DELIVERY";
                long userVisibleStartedAtMs = System.currentTimeMillis();
                boolean hasVisibleDeltas = finalStreamingEnabled && !pendingFinalDeltas.isEmpty();
                if (finalStreamingEnabled) {
                    deltaDelivery = publishValidatedFinalDeltas(pendingFinalDeltas);
                }
                Long userVisibleTtftMs = !hasVisibleDeltas || finalLogicalRequestStartedAtMs == 0
                        ? null
                        : Math.max(0, userVisibleStartedAtMs - finalLogicalRequestStartedAtMs);
                Long taskToFirstVisibleTokenMs = !hasVisibleDeltas || taskStartedAtMs == 0
                        ? null
                        : Math.max(0, userVisibleStartedAtMs - taskStartedAtMs);
                finalStreamMetrics = withVisibleTiming(finalStreamMetrics, userVisibleTtftMs,
                        taskToFirstVisibleTokenMs);
                FinalMessageDeliveryResult messageDelivery = publishPersistedFinalMessage(
                        durable.messageId(), finalAnswer);
                deliveredFinalCharCount = messageDelivery.delivered()
                        ? messageDelivery.contentCharCount() : 0;
                boolean deliveryCompleted = messageDelivery.delivered();

                pendingFinalAssistantMessage = null;
                pendingFinalDeltas = List.of();

                sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                        "stepId", durable.finalStepId(),
                        "stepNo", durable.finalStepNo(),
                        "stepType", "FINAL_SYNTHESIS",
                        "status", AgentTaskLogService.STATUS_SUCCESS
                ));
                sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                        "stepId", durable.finishStepId(),
                        "stepNo", durable.finishStepNo(),
                        "stepType", "FINISH",
                        "status", AgentTaskLogService.STATUS_SUCCESS
                ));

                if (finalStreamingEnabled) {
                    finalMessageDonePublished = true;
                    sendAgentEvent(AgentSseEvent.Type.FINAL_MESSAGE_DONE, payload(
                            "streamId", activeFinalStreamId,
                            "stepId", pendingFinalSynthesisStep.getId(),
                            "messageId", durable.messageId()
                    ));
                }
                sendAgentEvent(AgentSseEvent.Type.DONE, payload(
                        "status", AgentTaskLogService.STATUS_SUCCESS,
                        "finishReason", finalFinishReason
                ));
                String deliveryClassification = deliveryCompleted ? "NONE" : "FINAL_DELIVERY_FAILURE";
                publishFinalDeliveryObservation(messageId, assembledFinalCharCount, persistedFinalCharCount,
                        deltaDelivery, deliveredFinalCharCount, true, deliveryCompleted,
                        deliveryCompleted ? "NONE" : "SSE_DELIVERY", null, null, deliveryClassification);
                String deliveryEvent = deliveryCompleted ? "FinalDeliveryCompleted" : "FinalDeliveryFailed";
                log.info("event={} taskId={} streamId={} stepId={} finalAttempt={} messageId={} "
                                + "assembledFinalCharCount={} persistedFinalCharCount={} sseDeltaCount={} "
                                + "sseDeltaCharCount={} deliveredFinalCharCount={} persistenceCompleted={} "
                                + "deliveryCompleted={} failureClassification={} durableStartedAtMs={} "
                                + "durableFinishedAtMs={} firstTokenEmittedAtMs={}",
                        deliveryEvent, currentTaskId, activeFinalStreamId, pendingFinalSynthesisStep.getId(),
                        finalAttemptCount, messageId, assembledFinalCharCount, persistedFinalCharCount,
                        deltaDelivery.count(), deltaDelivery.charCount(), deliveredFinalCharCount,
                        true, deliveryCompleted, deliveryClassification, durableStartedAtMs, durableFinishedAtMs,
                        hasVisibleDeltas ? userVisibleStartedAtMs : null);
            } catch (RuntimeException e) {
                String classification = persistedFinalCharCount == 0
                        ? "FINAL_PERSISTENCE_FAILURE" : "FINAL_DELIVERY_FAILURE";
                publishFinalDeliveryObservation(messageId, assembledFinalCharCount, persistedFinalCharCount,
                        deltaDelivery, deliveredFinalCharCount, persistedFinalCharCount > 0, false,
                        failureStage, e.getClass().getSimpleName(), summarizeError(e), classification);
                log.warn("event=FinalDeliveryFailed taskId={} streamId={} stepId={} finalAttempt={} "
                                + "messageId={} assembledFinalCharCount={} persistedFinalCharCount={} "
                                + "sseDeltaCount={} sseDeltaCharCount={} deliveredFinalCharCount={} "
                                + "failureStage={} errorType={} errorMessage={} failureClassification={}",
                        currentTaskId, activeFinalStreamId, pendingFinalSynthesisStep.getId(), finalAttemptCount,
                        messageId, assembledFinalCharCount, persistedFinalCharCount,
                        deltaDelivery.count(), deltaDelivery.charCount(), deliveredFinalCharCount,
                        failureStage, e.getClass().getSimpleName(), summarizeError(e), classification);
                throw e;
            }
        };
        if (taskControl != null && !taskControl.completeIfActive(completion)) {
            throw new AgentTaskCancelledException(currentTaskId);
        }
        if (taskControl == null) {
            completion.run();
        }
    }

    private FinalMessageDeliveryResult publishPersistedFinalMessage(String messageId, String content) {
        ChatMessageDTO message = ChatMessageDTO.builder()
                .id(messageId)
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .sessionId(chatSessionId)
                .metadata(ChatMessageDTO.MetaData.builder().toolCalls(List.of()).build())
                .build();
        ChatMessageVO vo = chatMessageConverter.toVO(message);
        boolean delivered = agentEventPublisher.sendMessage(chatSessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder().message(vo).build())
                .metadata(SseMessage.Metadata.builder().chatMessageId(messageId).build())
                .build());
        int contentCharCount = vo == null || vo.getContent() == null ? 0 : vo.getContent().length();
        return new FinalMessageDeliveryResult(delivered, contentCharCount);
    }

    private void publishFinalDeliveryObservation(String messageId,
                                                 int assembledFinalCharCount,
                                                 int persistedFinalCharCount,
                                                 FinalDeltaDeliveryStats deltaDelivery,
                                                 int deliveredFinalCharCount,
                                                 boolean persistenceCompleted,
                                                 boolean deliveryCompleted,
                                                 String failureStage,
                                                 String errorType,
                                                 String errorMessage,
                                                 String failureClassification) {
        AgentLifecycleObservationPublisher.publishFinalDelivery(
                new AgentLifecycleObservationPublisher.FinalDeliveryObservation(
                        currentTaskId, chatSessionId, FINAL_PROVIDER_UNAVAILABLE, model,
                        finalAttemptCount, activeFinalStreamId,
                        pendingFinalSynthesisStep == null ? null : pendingFinalSynthesisStep.getId(), messageId,
                        assembledFinalCharCount, persistedFinalCharCount, deltaDelivery.count(),
                        deltaDelivery.charCount(), deliveredFinalCharCount, persistenceCompleted,
                        deliveryCompleted, failureStage, errorType, errorMessage, failureClassification));
    }

    private FinalStreamMetrics withVisibleTiming(FinalStreamMetrics metrics,
                                                 Long userVisibleTtftMs,
                                                 Long taskToFirstVisibleTokenMs) {
        return new FinalStreamMetrics(metrics.requestStartedAtEpochMs(), metrics.finalTtftMs(),
                metrics.finalTtltMs(),
                metrics.finalStreamDurationMs(), metrics.streamEventCount(), metrics.finalAnswerChars(),
                taskToFirstVisibleTokenMs, metrics.providerRequestId(), metrics.providerResponseModel(),
                metrics.providerFinishReason(), metrics.usage(), metrics.totalChunkCount(),
                metrics.contentBearingChunkCount(), metrics.rawContentCharCount(), metrics.metadataOnlyChunkCount(),
                metrics.terminalState(), metrics.streamCompletedNormally(), metrics.assembledFinalBlank(),
                metrics.reasoningEventCount(), metrics.reasoningChars(), metrics.finalAttemptCount(),
                metrics.unexpectedFinalToolCallCount(), metrics.unexpectedFinalToolCallRetryCount(),
                metrics.unexpectedFinalToolCallRetrySucceeded(), metrics.validationLatencyMs(),
                userVisibleTtftMs, metrics.finalValidationFailureCount(), metrics.finalCorrectiveRetryCount(),
                metrics.finalCorrectiveRetrySucceeded());
    }

    private String finalStreamSummary() {
        return "streamId=" + activeFinalStreamId
                + ", finalTtftMs=" + finalStreamMetrics.finalTtftMs()
                + ", finalTtltMs=" + finalStreamMetrics.finalTtltMs()
                + ", finalStreamDurationMs=" + finalStreamMetrics.finalStreamDurationMs()
                + ", streamEventCount=" + finalStreamMetrics.streamEventCount()
                + ", totalChunkCount=" + finalStreamMetrics.totalChunkCount()
                + ", contentBearingChunkCount=" + finalStreamMetrics.contentBearingChunkCount()
                + ", rawContentCharCount=" + finalStreamMetrics.rawContentCharCount()
                + ", finalAnswerChars=" + finalStreamMetrics.finalAnswerChars()
                + ", assembledFinalBlank=" + finalStreamMetrics.assembledFinalBlank()
                + ", taskToFirstVisibleTokenMs=" + finalStreamMetrics.taskToFirstVisibleTokenMs()
                + ", providerRequestId=" + finalStreamMetrics.providerRequestId()
                + ", providerFinishReason=" + finalStreamMetrics.providerFinishReason()
                + ", usage=" + finalStreamMetrics.usageSummary()
                + ", reasoningEventCount=" + finalStreamMetrics.reasoningEventCount()
                + ", reasoningChars=" + finalStreamMetrics.reasoningChars()
                + ", finalAttemptCount=" + finalStreamMetrics.finalAttemptCount()
                + ", unexpectedFinalToolCallCount=" + finalStreamMetrics.unexpectedFinalToolCallCount()
                + ", unexpectedFinalToolCallRetryCount=" + finalStreamMetrics.unexpectedFinalToolCallRetryCount()
                + ", unexpectedFinalToolCallRetrySucceeded="
                + finalStreamMetrics.unexpectedFinalToolCallRetrySucceeded()
                + ", validationLatencyMs=" + finalStreamMetrics.validationLatencyMs()
                + ", userVisibleTtftMs=" + finalStreamMetrics.userVisibleTtftMs()
                + ", finalValidationFailureCount=" + finalStreamMetrics.finalValidationFailureCount()
                + ", finalCorrectiveRetryCount=" + finalStreamMetrics.finalCorrectiveRetryCount()
                + ", finalCorrectiveRetrySucceeded=" + finalStreamMetrics.finalCorrectiveRetrySucceeded();
    }

    private void throwIfCancellationRequested() {
        if (taskControl != null) {
            taskControl.throwIfCancellationRequested();
        }
    }

    private void handleCancellation() {
        agentState = AgentState.FINISHED;
        Runnable cancellation = () -> {
            agentTaskLogService.cancelStepAndTask(currentStep == null ? null : currentStep.getId(),
                    currentTaskId, nextStepNo - 1, toolCallCount);
            sendAgentEvent(AgentSseEvent.Type.CANCELLED, payload(
                    "status", AgentTaskLogService.STATUS_CANCELLED,
                    "finishReason", AgentTaskLogService.FINISH_REASON_CANCELLED,
                    "discardedToolMessages", 0
            ));
            agentEventPublisher.complete(chatSessionId, currentTaskId);
        };
        if (taskControl == null) {
            cancellation.run();
        } else {
            taskControl.completeCancellation(cancellation);
        }
        pendingFinalAssistantMessage = null;
    }

    private final class FinalStreamSubscriber extends BaseSubscriber<ChatResponse> {
        private final String streamId;
        private final String stepId;
        private final long requestStartedAtMs;
        private final CountDownLatch terminalSignal = new CountDownLatch(1);
        private final StringBuilder answerBuffer = new StringBuilder();
        private final List<String> deltas = new ArrayList<>();
        private int sequence;
        private long firstVisibleAtMs;
        private long completedAtMs;
        private Throwable error;
        private String providerFinishReason;
        private String providerRequestId;
        private String providerResponseModel;
        private Usage usage;
        private int totalChunkCount;
        private int rawContentCharCount;
        private int metadataOnlyChunkCount;
        private int reasoningEventCount;
        private int reasoningChars;
        private boolean unexpectedToolCall;
        private SignalType terminalSignalType;

        private FinalStreamSubscriber(String streamId, String stepId, long requestStartedAtMs) {
            this.streamId = streamId;
            this.stepId = stepId;
            this.requestStartedAtMs = requestStartedAtMs;
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            if (taskControl != null) {
                taskControl.attachActiveStream(this);
            }
            if (!isDisposed()) {
                requestUnbounded();
            }
        }

        @Override
        protected void hookOnNext(ChatResponse response) {
            totalChunkCount++;
            captureProviderMetadata(response);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                metadataOnlyChunkCount++;
                return;
            }
            AssistantMessage output = response.getResult().getOutput();
            String chunkFinishReason = response.getResult().getMetadata() == null
                    ? null
                    : response.getResult().getMetadata().getFinishReason();
            if (StringUtils.hasText(chunkFinishReason)) {
                providerFinishReason = chunkFinishReason;
            }
            Usage chunkUsage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (hasAvailableUsage(chunkUsage)) {
                usage = chunkUsage;
            }
            boolean reasoningBearing = output instanceof DeepSeekAssistantMessage deepSeekMessage
                    && StringUtils.hasLength(deepSeekMessage.getReasoningContent());
            if (reasoningBearing) {
                DeepSeekAssistantMessage deepSeekMessage = (DeepSeekAssistantMessage) output;
                reasoningEventCount++;
                reasoningChars += deepSeekMessage.getReasoningContent().length();
            }
            if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
                error = new IllegalStateException("Final synthesis returned a tool call while tools were disabled");
                unexpectedToolCall = true;
                cancel();
                return;
            }
            String delta = output.getText();
            if (delta == null || delta.isEmpty()) {
                if (!reasoningBearing && (output.getToolCalls() == null || output.getToolCalls().isEmpty())) {
                    metadataOnlyChunkCount++;
                }
                return;
            }
            rawContentCharCount += delta.length();
            long now = System.currentTimeMillis();
            if (firstVisibleAtMs == 0) {
                firstVisibleAtMs = now;
            }
            answerBuffer.append(delta);
            deltas.add(delta);
            sequence++;
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            if (error == null) {
                error = throwable;
            }
        }

        @Override
        protected void hookFinally(SignalType type) {
            terminalSignalType = type;
            completedAtMs = System.currentTimeMillis();
            if (taskControl != null) {
                taskControl.detachActiveStream(this);
            }
            terminalSignal.countDown();
        }

        private FinalStreamResult awaitResult() {
            try {
                terminalSignal.await();
            } catch (InterruptedException e) {
                dispose();
                Thread.currentThread().interrupt();
                throwIfCancellationRequested();
                throw new IllegalStateException("Interrupted while waiting for final synthesis stream", e);
            }
            throwIfCancellationRequested();
            return snapshotResult(null);
        }

        private FinalStreamResult snapshotResult(Throwable fallbackError) {
            long effectiveCompletedAtMs = completedAtMs == 0 ? System.currentTimeMillis() : completedAtMs;
            Long ttftMs = firstVisibleAtMs == 0 ? null : Math.max(0, firstVisibleAtMs - requestStartedAtMs);
            Long streamDurationMs = firstVisibleAtMs == 0
                    ? null
                    : Math.max(0, effectiveCompletedAtMs - firstVisibleAtMs);
            Long taskToFirstVisibleTokenMs = firstVisibleAtMs == 0 || taskStartedAtMs == 0
                    ? null
                    : Math.max(0, firstVisibleAtMs - taskStartedAtMs);
            FinalStreamMetrics metrics = new FinalStreamMetrics(
                    requestStartedAtMs,
                    ttftMs,
                    Math.max(0, effectiveCompletedAtMs - requestStartedAtMs),
                    streamDurationMs,
                    sequence,
                    answerBuffer.length(),
                    taskToFirstVisibleTokenMs,
                    providerRequestId,
                    providerResponseModel,
                    providerFinishReason,
                    usage,
                    totalChunkCount,
                    sequence,
                    rawContentCharCount,
                    metadataOnlyChunkCount,
                    terminalState(fallbackError),
                    terminalSignalType == SignalType.ON_COMPLETE && error == null && fallbackError == null,
                    !StringUtils.hasText(answerBuffer),
                    reasoningEventCount,
                    reasoningChars,
                    0,
                    0,
                    0,
                    false,
                    0,
                    null,
                    0,
                    0,
                    false
            );
            return new FinalStreamResult(answerBuffer.toString(), List.copyOf(deltas),
                    error == null ? fallbackError : error, unexpectedToolCall, metrics);
        }

        private void captureProviderMetadata(ChatResponse response) {
            if (response == null || response.getMetadata() == null) {
                return;
            }
            if (!StringUtils.hasText(providerRequestId)
                    && StringUtils.hasText(response.getMetadata().getId())) {
                providerRequestId = response.getMetadata().getId();
            }
            if (!StringUtils.hasText(providerResponseModel)
                    && StringUtils.hasText(response.getMetadata().getModel())) {
                providerResponseModel = response.getMetadata().getModel();
            }
        }

        private String terminalState(Throwable fallbackError) {
            if (error != null || fallbackError != null || terminalSignalType == SignalType.ON_ERROR) {
                return "ERROR";
            }
            if (terminalSignalType == SignalType.ON_COMPLETE) {
                return "COMPLETED";
            }
            if (terminalSignalType == SignalType.CANCEL) {
                return "CANCELLED";
            }
            return terminalSignalType == null ? "UNKNOWN" : terminalSignalType.name();
        }

        private boolean hasAvailableUsage(Usage chunkUsage) {
            if (chunkUsage == null) {
                return false;
            }
            return positive(chunkUsage.getPromptTokens())
                    || positive(chunkUsage.getCompletionTokens())
                    || positive(chunkUsage.getTotalTokens());
        }

        private boolean positive(Integer value) {
            return value != null && value > 0;
        }
    }

    private record FinalStreamResult(String answer, List<String> deltas,
                                     Throwable error, boolean unexpectedToolCall,
                                     FinalStreamMetrics metrics) {
    }

    private record FinalDeltaDeliveryStats(int count, int charCount) {
        private static FinalDeltaDeliveryStats empty() {
            return new FinalDeltaDeliveryStats(0, 0);
        }
    }

    private record FinalMessageDeliveryResult(boolean delivered, int contentCharCount) {
    }

    private record FinalStreamMetrics(long requestStartedAtEpochMs,
                                      Long finalTtftMs,
                                      Long finalTtltMs,
                                      Long finalStreamDurationMs,
                                      int streamEventCount,
                                      int finalAnswerChars,
                                      Long taskToFirstVisibleTokenMs,
                                      String providerRequestId,
                                      String providerResponseModel,
                                      String providerFinishReason,
                                      Usage usage,
                                      int totalChunkCount,
                                      int contentBearingChunkCount,
                                      int rawContentCharCount,
                                      int metadataOnlyChunkCount,
                                      String terminalState,
                                      boolean streamCompletedNormally,
                                      boolean assembledFinalBlank,
                                      int reasoningEventCount,
                                      int reasoningChars,
                                      int finalAttemptCount,
                                      int unexpectedFinalToolCallCount,
                                      int unexpectedFinalToolCallRetryCount,
                                      boolean unexpectedFinalToolCallRetrySucceeded,
                                      long validationLatencyMs,
                                      Long userVisibleTtftMs,
                                      int finalValidationFailureCount,
                                      int finalCorrectiveRetryCount,
                                      boolean finalCorrectiveRetrySucceeded) {
        private String usageSummary() {
            if (usage == null) {
                return "UNAVAILABLE";
            }
            return "promptTokens=" + usage.getPromptTokens()
                    + ",completionTokens=" + usage.getCompletionTokens()
                    + ",totalTokens=" + usage.getTotalTokens();
        }
    }

    public void setMaxLoopSteps(int maxLoopSteps) {
        this.maxLoopSteps = Math.max(1, maxLoopSteps);
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public void setTrustedRepoId(String trustedRepoId) {
        this.trustedRepoId = StringUtils.hasText(trustedRepoId) ? trustedRepoId.trim() : null;
    }

    public void setTaskRuntimeRegistry(AgentTaskRuntimeRegistry taskRuntimeRegistry) {
        this.taskRuntimeRegistry = taskRuntimeRegistry;
    }

    public void setFinalStreamingEnabled(boolean finalStreamingEnabled) {
        this.finalStreamingEnabled = finalStreamingEnabled;
    }

    void setPlanningGenerationOptions(Double temperature, Double topP) {
        this.chatOptions = createPlanningChatOptions(temperature, topP);
    }

    public void setFinalCompletionService(FinalCompletionService finalCompletionService) {
        this.finalCompletionService = finalCompletionService;
    }

    public void setFinalContextCompiler(FinalContextCompiler finalContextCompiler) {
        Assert.notNull(finalContextCompiler, "FinalContextCompiler cannot be null");
        this.finalContextCompiler = finalContextCompiler;
    }

    public String getFinishReason() {
        return finishReason;
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
