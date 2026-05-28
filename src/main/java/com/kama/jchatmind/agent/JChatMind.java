package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.config.ToolCorrectionProperties;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.AgentStep;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolFailureDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;

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
    private String currentTaskId;
    private AgentStep currentStep;
    private AgentExecutionContext agentExecutionContext;
    private List<String> runtimeToolNames;
    private ToolCorrectionProperties toolCorrectionProperties = new ToolCorrectionProperties();
    private ToolFailureClassifier toolFailureClassifier = new ToolFailureClassifier();
    private int nextStepNo = 1;
    private int toolCallCount = 0;
    private String finishReason;
    private final Map<String, Integer> toolCorrectionAttempts = new HashMap<>();

    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

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
                     ToolFailureClassifier toolFailureClassifier) {
        this(agentId, model, name, description, systemPrompt, chatClient, maxMessages, memory,
                availableTools, availableKbs, chatSessionId, sseService, new AgentEventPublisher(sseService),
                toolExecutionService, chatMessageFacadeService, chatMessageConverter, agentTaskLogService,
                conversationContextCompressor, userMessageId, runtimeToolNames, toolCorrectionProperties,
                toolFailureClassifier, null, null);
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
        this.toolCallBatchExecutor = toolCallBatchExecutor == null
                ? new ToolCallBatchExecutor(toolExecutionService)
                : toolCallBatchExecutor;
        this.conversationContextCompressor = conversationContextCompressor;
        this.userMessageId = userMessageId;
        this.runtimeToolNames = runtimeToolNames == null ? List.of() : runtimeToolNames;
        if (toolCorrectionProperties != null) {
            this.toolCorrectionProperties = toolCorrectionProperties;
        }
        if (toolFailureClassifier != null) {
            this.toolFailureClassifier = toolFailureClassifier;
        }
        this.agentState = AgentState.IDLE;

        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();

        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }
        this.chatMemory.add(chatSessionId, memory);

        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
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

    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage) {
            AssistantMessage assistantMessage = (AssistantMessage) message;
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage) {
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) message;
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + message.getClass().getName());
        }
    }

    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            if (!isUserVisibleMessage(message)) {
                continue;
            }
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder().message(vo).build())
                    .metadata(SseMessage.Metadata.builder().chatMessageId(message.getId()).build())
                    .build();
            agentEventPublisher.sendMessage(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    private boolean isUserVisibleMessage(ChatMessageDTO message) {
        if (message == null || message.getRole() != ChatMessageDTO.RoleType.ASSISTANT) {
            return false;
        }
        return message.getMetadata() == null
                || message.getMetadata().getToolCalls() == null
                || message.getMetadata().getToolCalls().isEmpty();
    }

    private void sendAgentEvent(AgentSseEvent.Type type, Map<String, Object> payload) {
        agentEventPublisher.publish(currentTaskId, this.chatSessionId, type, payload);
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

    private List<Message> toMemoryMessages(ConversationContextCompressor.CompressedContext compressedContext) {
        List<Message> memory = new ArrayList<>();
        if (StringUtils.hasLength(systemPrompt)) {
            memory.add(new SystemMessage(systemPrompt));
        }
        if (StringUtils.hasLength(compressedContext.summary())) {
            memory.add(new SystemMessage("[Conversation summary]\n" + compressedContext.summary()
                    + "\n\nNote: The summary is only auxiliary context. If it conflicts with recent user input or retrieval results, prefer the recent input and retrieval results."));
        }
        for (ChatMessageDTO chatMessageDTO : compressedContext.recentMessages()) {
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

    private void compressContextBeforeThinkIfNeeded() {
        if (conversationContextCompressor == null || currentTaskId == null) {
            return;
        }
        List<ChatMessageDTO> allMessages = chatMessageFacadeService.getChatMessageDTOsBySessionId(chatSessionId);
        ConversationContextCompressor.CompressionCheck check =
                conversationContextCompressor.check(chatSessionId, allMessages);
        if (!check.needed()) {
            return;
        }

        AgentStep compressionStep = startStep("CONTEXT_COMPRESSION",
                "reason=" + check.reason()
                        + ", messages=" + check.messageCount()
                        + ", contextTokens=" + check.contextTokens()
                        + ", maxToolResultTokens=" + check.maxSingleToolResultTokens()
                        + ", newCompressibleMessages=" + check.newCompressibleMessages());
        try {
            ConversationContextCompressor.CompressedContext compressedContext =
                    conversationContextCompressor.compressIfNeeded(chatSessionId, model, allMessages);
            if (compressedContext.compressed()) {
                this.chatMemory.clear(this.chatSessionId);
                this.chatMemory.add(this.chatSessionId, toMemoryMessages(compressedContext));
            }
            agentTaskLogService.finishStep(compressionStep.getId(),
                    "compressed=" + compressedContext.compressed()
                            + ", summaryChars=" + (compressedContext.summary() == null ? 0 : compressedContext.summary().length())
                            + ", recentMessages=" + compressedContext.recentMessages().size());
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

    private boolean think() {
        String thinkPrompt = "You are the decision module of an intelligent agent.\n"
                + "Decide the next action from the current conversation context.\n\n"
                + "Extra information:\n"
                + "- Available knowledge bases: " + this.availableKbs + "\n"
                + "- If context is missing, prefer searching the knowledge base first.";

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        saveMessage(output);
        refreshPendingMessages();
        logToolCalls(toolCalls);

        return toolCalls != null && !toolCalls.isEmpty();
    }

    private boolean execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");
        if (!this.lastChatResponse.hasToolCalls()) {
            return false;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        List<AssistantMessage.ToolCall> toolCalls = this.lastChatResponse.getResult().getOutput().getToolCalls();
        ToolExecutionContext executionContext = ToolExecutionContext.builder()
                .taskId(currentTaskId)
                .stepId(currentStep == null ? null : currentStep.getId())
                .traceId(agentExecutionContext == null ? null : agentExecutionContext.getTraceId())
                .sessionId(chatSessionId)
                .agentId(agentId)
                .modelName(model)
                .runtimeToolNames(runtimeToolNames)
                .build();

        ToolCallBatchResult execution = toolCallBatchExecutor.execute(
                prompt,
                this.lastChatResponse,
                toolCallingManager,
                executionContext
        );
        List<ToolExecutionRecord> records = execution.getRecords();
        toolCallCount += records.size();

        if (!execution.succeeded()) {
            if (tryRequestToolSelfCorrection(executionContext, records, execution.getError())) {
                return true;
            }
            toolCallBatchExecutor.recordFailure(executionContext, records, execution.getError(), false);
            throw execution.getError();
        }

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, execution.getToolExecutionResult().conversationHistory());

        ToolResponseMessage toolResponseMessage = execution.getToolResponseMessage();
        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "Tool " + resp.name() + " result: " + truncate(resp.responseData()))
                .collect(Collectors.joining("\n"));
        log.info("Tool call result: {}", collect);

        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses().stream().anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            this.finishReason = AgentTaskLogService.FINISH_REASON_TERMINATE_TOOL;
            log.info("Agent task terminated by terminate tool");
        }
        return false;
    }

    private boolean tryRequestToolSelfCorrection(ToolExecutionContext executionContext,
                                                 List<ToolExecutionRecord> records,
                                                 Exception error) {
        if (!toolCorrectionProperties.isEnabled() || records.isEmpty()) {
            return false;
        }
        ToolFailureDecision decision = toolFailureClassifier.classify(error);
        if (!decision.correctable()) {
            return false;
        }
        if (!reserveCorrectionAttempts(records, decision.errorType())) {
            return false;
        }

        toolCallBatchExecutor.recordFailure(executionContext, records, error, true);
        ToolResponseMessage failureResponseMessage = buildFailureToolResponseMessage(records, decision);
        List<Message> correctedMemory = new ArrayList<>(this.chatMemory.get(this.chatSessionId));
        correctedMemory.add(this.lastChatResponse.getResult().getOutput());
        correctedMemory.add(failureResponseMessage);
        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, correctedMemory);
        saveMessage(failureResponseMessage);
        refreshPendingMessages();
        log.info("Tool failure fed back for self-correction: errorType={}, attempts={}",
                decision.errorType(), toolCorrectionAttempts);
        return true;
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

    private ToolResponseMessage buildFailureToolResponseMessage(List<ToolExecutionRecord> records,
                                                                ToolFailureDecision decision) {
        List<ToolResponseMessage.ToolResponse> responses = records.stream()
                .map(record -> new ToolResponseMessage.ToolResponse(
                        record.getToolCallId(),
                        record.getActualToolName(),
                        correctionPayload(record, decision)
                ))
                .collect(Collectors.toList());
        return ToolResponseMessage.builder()
                .responses(responses)
                .build();
    }

    private String correctionPayload(ToolExecutionRecord record, ToolFailureDecision decision) {
        return "Tool call failed:\n"
                + "toolName=" + record.getActualToolName() + "\n"
                + "errorType=" + decision.errorType() + "\n"
                + "message=" + decision.sanitizedMessage() + "\n"
                + "correctionHint=" + decision.correctionHint();
    }

    private void step(int loopStep) {
        compressContextBeforeThinkIfNeeded();

        AgentStep thinkStep = startStep("THINK", "think with current conversation memory");

        boolean hasToolCalls;
        long thinkStartedAt = System.currentTimeMillis();
        hasToolCalls = think();
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
            agentState = AgentState.FINISHED;
            finishReason = AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS;
        }
    }

    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        String traceId = UUID.randomUUID().toString();
        AgentTask task = agentTaskLogService.startTask(this.chatSessionId, this.agentId, this.userMessageId,
                "chat session agent run", this.model, MAX_STEPS, traceId);
        this.currentTaskId = task.getId();
        this.agentExecutionContext = AgentExecutionContext.builder()
                .taskId(currentTaskId)
                .traceId(traceId)
                .sessionId(chatSessionId)
                .agentId(agentId)
                .modelName(model)
                .maxSteps(MAX_STEPS)
                .build();
        sendAgentEvent(AgentSseEvent.Type.MESSAGE_START, payload(
                "taskId", currentTaskId,
                "traceId", traceId,
                "agentId", this.agentId,
                "userMessageId", this.userMessageId
        ));

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                int loopStep = i + 1;
                step(loopStep);
                if (loopStep >= MAX_STEPS && agentState != AgentState.FINISHED) {
                    agentState = AgentState.FINISHED;
                    finishReason = AgentTaskLogService.FINISH_REASON_MAX_STEPS_REACHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }

            agentState = AgentState.FINISHED;
            AgentStep finishStep = startStep("FINISH", "finish agent run");
            String finalFinishReason = finishReason == null
                    ? AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS
                    : finishReason;
            agentTaskLogService.finishStep(finishStep.getId(), "agent finished", finalFinishReason, null);
            sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                    "stepId", finishStep.getId(),
                    "stepNo", finishStep.getStepNo(),
                    "stepType", finishStep.getStepType(),
                    "status", AgentTaskLogService.STATUS_SUCCESS
            ));
            agentTaskLogService.finishTask(currentTaskId, finalFinishReason, nextStepNo - 1, toolCallCount);
            sendAgentEvent(AgentSseEvent.Type.DONE, payload(
                    "status", AgentTaskLogService.STATUS_SUCCESS,
                    "finishReason", finalFinishReason
            ));
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            agentRunFailureHandler.handle(currentTaskId, chatSessionId, currentStep,
                    nextStepNo - 1, toolCallCount, e);
            throw new RuntimeException("Error running agent", e);
        } finally {
            agentExecutionContext = null;
        }
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
