package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

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

    private static boolean hasToolCalls(ChatMessageDTO chatMessageDTO) {
        return chatMessageDTO.getMetadata() != null
                && chatMessageDTO.getMetadata().getToolCalls() != null
                && !chatMessageDTO.getMetadata().getToolCalls().isEmpty();
    }
}
