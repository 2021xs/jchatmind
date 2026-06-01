package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AgentMemoryHistorySanitizer {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryHistorySanitizer.class);

    private AgentMemoryHistorySanitizer() {
    }

    static List<Message> toSafeReplayMessages(String summary, List<ChatMessageDTO> chatMessages) {
        List<Message> memory = new ArrayList<>();
        if (StringUtils.hasLength(summary)) {
            memory.add(new SystemMessage("[Conversation summary]\n" + summary
                    + "\n\nNote: The summary is only auxiliary context. If it conflicts with recent user input or retrieval results, prefer the recent input and retrieval results."));
        }
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    }
                    break;
                case USER:
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(new UserMessage(chatMessageDTO.getContent()));
                    }
                    break;
                case ASSISTANT:
                    if (hasToolCalls(chatMessageDTO)) {
                        log.debug("Skip assistant tool-call history during model replay: messageId={}",
                                chatMessageDTO.getId());
                        break;
                    }
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(AssistantMessage.builder()
                                .content(chatMessageDTO.getContent())
                                .toolCalls(List.of())
                                .build());
                    }
                    break;
                case TOOL:
                    log.debug("Skip tool response history during model replay: messageId={}", chatMessageDTO.getId());
                    break;
                default:
                    log.error("Unsupported message type: {}, content={}",
                            chatMessageDTO.getRole().getRole(), chatMessageDTO.getContent());
                    throw new IllegalStateException("Unsupported message type");
            }
        }
        return memory;
    }

    static List<Message> toSafeModelMessages(List<Message> messages) {
        List<Message> safeMessages = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage) {
                log.debug("Skip orphan tool response message during model replay");
                continue;
            }
            if (message instanceof AssistantMessage assistantMessage && hasToolCalls(assistantMessage)) {
                List<ToolResponseMessage> toolResponseMessages = nextToolResponses(messages, i + 1);
                if (!matchesToolCalls(assistantMessage, toolResponseMessages)) {
                    log.debug("Skip assistant tool-call message without matching tool response during model replay");
                    continue;
                }
                safeMessages.add(assistantMessage);
                safeMessages.addAll(toolResponseMessages);
                i += toolResponseMessages.size();
                continue;
            }
            safeMessages.add(message);
        }
        return safeMessages;
    }

    private static boolean hasToolCalls(ChatMessageDTO chatMessageDTO) {
        return chatMessageDTO.getMetadata() != null
                && chatMessageDTO.getMetadata().getToolCalls() != null
                && !chatMessageDTO.getMetadata().getToolCalls().isEmpty();
    }

    private static boolean hasToolCalls(AssistantMessage assistantMessage) {
        return assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty();
    }

    private static List<ToolResponseMessage> nextToolResponses(List<Message> messages, int index) {
        List<ToolResponseMessage> responses = new ArrayList<>();
        for (int i = index; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage toolResponseMessage)) {
                break;
            }
            responses.add(toolResponseMessage);
        }
        return responses;
    }

    private static boolean matchesToolCalls(AssistantMessage assistantMessage,
                                            List<ToolResponseMessage> toolResponseMessages) {
        Set<String> toolCallIds = new HashSet<>();
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (StringUtils.hasLength(toolCall.id())) {
                toolCallIds.add(toolCall.id());
            }
        }
        if (toolCallIds.isEmpty()) {
            return !toolResponseMessages.isEmpty()
                    && toolResponseMessages.stream().anyMatch(response -> !response.getResponses().isEmpty());
        }
        Set<String> responseIds = new HashSet<>();
        toolResponseMessages.stream()
                .flatMap(response -> response.getResponses().stream())
                .filter(response -> StringUtils.hasLength(response.id()))
                .forEach(response -> responseIds.add(response.id()));
        return responseIds.equals(toolCallIds);
    }
}
