package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FeishuAgentRunAdapter {

    private static final int RECENT_MESSAGE_LIMIT = 30;

    private final JChatMindFactory jChatMindFactory;
    private final ChatMessageFacadeService chatMessageFacadeService;

    public AgentRunResult run(String agentId, String chatId, String question) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("Feishu default agent id is not configured");
        }
        String sessionId = toSessionId(chatId);
        CreateChatMessageResponse userMessage = chatMessageFacadeService.agentCreateChatMessage(
                CreateChatMessageRequest.builder()
                        .agentId(agentId)
                        .sessionId(sessionId)
                        .role(ChatMessageDTO.RoleType.USER)
                        .content(question)
                        .build());

        JChatMind agent = jChatMindFactory.create(agentId, sessionId, userMessage.getChatMessageId());
        agent.run();

        String answer = loadLatestAssistantAnswer(sessionId);
        return new AgentRunResult(sessionId, userMessage.getChatMessageId(), answer);
    }

    String toSessionId(String chatId) {
        String source = "feishu:" + (StringUtils.hasText(chatId) ? chatId : "unknown");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String loadLatestAssistantAnswer(String sessionId) {
        List<ChatMessageDTO> recentMessages =
                chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, RECENT_MESSAGE_LIMIT);
        return recentMessages.stream()
                .filter(this::isVisibleAssistantMessage)
                .max(Comparator.comparing(ChatMessageDTO::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ChatMessageDTO::getContent)
                .filter(StringUtils::hasText)
                .orElse("Agent run completed, but no final assistant answer was saved.");
    }

    private boolean isVisibleAssistantMessage(ChatMessageDTO message) {
        if (message == null || message.getRole() != ChatMessageDTO.RoleType.ASSISTANT) {
            return false;
        }
        return message.getMetadata() == null
                || message.getMetadata().getToolCalls() == null
                || message.getMetadata().getToolCalls().isEmpty();
    }

    public record AgentRunResult(String sessionId, String userMessageId, String answer) {
    }
}
