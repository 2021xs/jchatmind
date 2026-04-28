package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
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
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
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
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    private String agentId;
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
    private SseService sseService;
    private ToolExecutionService toolExecutionService;
    private ChatMessageConverter chatMessageConverter;
    private ChatMessageFacadeService chatMessageFacadeService;
    private ChatResponse lastChatResponse;
    private AgentTaskLogService agentTaskLogService;
    private String userMessageId;
    private String currentTaskId;
    private AgentStep currentStep;
    private List<String> runtimeToolNames;

    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public JChatMind() {
    }

    public JChatMind(String agentId,
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
                     String userMessageId,
                     List<String> runtimeToolNames) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClient;
        this.availableTools = availableTools;
        this.availableKbs = availableKbs;
        this.chatSessionId = chatSessionId;
        this.sseService = sseService;
        this.toolExecutionService = toolExecutionService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.agentTaskLogService = agentTaskLogService;
        this.userMessageId = userMessageId;
        this.runtimeToolNames = runtimeToolNames == null ? List.of() : runtimeToolNames;
        this.agentState = AgentState.IDLE;

        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

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
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder().message(vo).build())
                    .metadata(SseMessage.Metadata.builder().chatMessageId(message.getId()).build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    private void sendAgentEvent(AgentSseEvent.Type type, Map<String, Object> payload) {
        if (currentTaskId == null) {
            return;
        }
        try {
            sseService.sendEvent(this.chatSessionId, AgentSseEvent.of(currentTaskId, this.chatSessionId, type, payload));
        } catch (Exception e) {
            log.warn("Failed to send Agent SSE event: type={}, error={}", type, e.getMessage());
        }
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

    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");
        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        List<AssistantMessage.ToolCall> toolCalls = this.lastChatResponse.getResult().getOutput().getToolCalls();
        ToolExecutionContext executionContext = ToolExecutionContext.builder()
                .taskId(currentTaskId)
                .stepId(currentStep == null ? null : currentStep.getId())
                .sessionId(chatSessionId)
                .runtimeToolNames(runtimeToolNames)
                .build();

        List<ToolExecutionRecord> records = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            records.add(toolExecutionService.beforeToolCall(executionContext, toolCall));
        }

        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);
        } catch (Exception e) {
            for (ToolExecutionRecord record : records) {
                toolExecutionService.afterToolFailure(executionContext, record, e);
            }
            throw e;
        }

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
        for (int i = 0; i < responses.size() && i < records.size(); i++) {
            // Spring AI's ToolResponse is matched to the preflight record by response order here.
            // ToolCall.id is stored when present, but this response object is not relied on for id matching.
            toolExecutionService.afterToolSuccess(executionContext, records.get(i), responses.get(i).responseData());
        }
        for (int i = responses.size(); i < records.size(); i++) {
            toolExecutionService.afterToolFailure(
                    executionContext,
                    records.get(i),
                    new IllegalStateException("Tool response missing for call index " + i)
            );
        }

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "Tool " + resp.name() + " result: " + truncate(resp.responseData()))
                .collect(Collectors.joining("\n"));
        log.info("Tool call result: {}", collect);

        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses().stream().anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("Agent task terminated by terminate tool");
        }
    }

    private void step(int loopStep) {
        AgentStep thinkStep = agentTaskLogService.startStep(
                currentTaskId,
                loopStep * 2 - 1,
                "THINK",
                "think with current conversation memory"
        );
        currentStep = thinkStep;
        AgentExecutionContext.setCurrentStepId(thinkStep.getId());
        AgentExecutionContext.setStepNo(thinkStep.getStepNo());

        boolean hasToolCalls;
        try {
            hasToolCalls = think();
            List<AssistantMessage.ToolCall> toolCalls = lastChatResponse.getResult().getOutput().getToolCalls();
            agentTaskLogService.finishStep(thinkStep.getId(), summarizeToolCalls(toolCalls));
            sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                    "stepId", thinkStep.getId(),
                    "stepNo", thinkStep.getStepNo(),
                    "stepType", thinkStep.getStepType(),
                    "status", AgentTaskLogService.STATUS_SUCCESS
            ));
        } catch (Exception e) {
            agentTaskLogService.failStep(thinkStep.getId(), e.getMessage());
            sendAgentEvent(AgentSseEvent.Type.ERROR, payload(
                    "stepId", thinkStep.getId(),
                    "stepNo", thinkStep.getStepNo(),
                    "errorMessage", truncate(e.getMessage())
            ));
            throw e;
        }

        if (hasToolCalls) {
            AgentStep toolStep = agentTaskLogService.startStep(
                    currentTaskId,
                    loopStep * 2,
                    "TOOL_CALL",
                    summarizeToolCalls(lastChatResponse.getResult().getOutput().getToolCalls())
            );
            currentStep = toolStep;
            AgentExecutionContext.setCurrentStepId(toolStep.getId());
            AgentExecutionContext.setStepNo(toolStep.getStepNo());
            try {
                execute();
                agentTaskLogService.finishStep(toolStep.getId(), "tool calls executed");
                sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                        "stepId", toolStep.getId(),
                        "stepNo", toolStep.getStepNo(),
                        "stepType", toolStep.getStepType(),
                        "status", AgentTaskLogService.STATUS_SUCCESS
                ));
            } catch (Exception e) {
                agentTaskLogService.failStep(toolStep.getId(), e.getMessage());
                sendAgentEvent(AgentSseEvent.Type.ERROR, payload(
                        "stepId", toolStep.getId(),
                        "stepNo", toolStep.getStepNo(),
                        "errorMessage", truncate(e.getMessage())
                ));
                throw e;
            }
        } else {
            agentState = AgentState.FINISHED;
        }
    }

    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        AgentTask task = agentTaskLogService.startTask(this.chatSessionId, this.agentId, this.userMessageId, "chat session agent run");
        this.currentTaskId = task.getId();
        AgentExecutionContext.set(new AgentExecutionContext.Context(currentTaskId, chatSessionId));
        sendAgentEvent(AgentSseEvent.Type.MESSAGE_START, payload(
                "agentId", this.agentId,
                "userMessageId", this.userMessageId
        ));

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                int loopStep = i + 1;
                step(loopStep);
                if (loopStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }

            agentState = AgentState.FINISHED;
            AgentStep finishStep = agentTaskLogService.startStep(
                    currentTaskId,
                    MAX_STEPS * 2 + 1,
                    "FINISH",
                    "finish agent run"
            );
            agentTaskLogService.finishStep(finishStep.getId(), "agent finished");
            sendAgentEvent(AgentSseEvent.Type.STEP_DONE, payload(
                    "stepId", finishStep.getId(),
                    "stepNo", finishStep.getStepNo(),
                    "stepType", finishStep.getStepType(),
                    "status", AgentTaskLogService.STATUS_SUCCESS
            ));
            agentTaskLogService.finishTask(currentTaskId);
            sendAgentEvent(AgentSseEvent.Type.DONE, payload("status", AgentTaskLogService.STATUS_SUCCESS));
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            if (currentStep != null) {
                agentTaskLogService.failStep(currentStep.getId(), e.getMessage());
            }
            agentTaskLogService.failTask(currentTaskId, e.getMessage());
            sendAgentEvent(AgentSseEvent.Type.ERROR, payload(
                    "status", AgentTaskLogService.STATUS_FAILED,
                    "errorMessage", truncate(e.getMessage())
            ));
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        } finally {
            AgentExecutionContext.clear();
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
