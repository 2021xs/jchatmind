package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.FeishuAgentSessionBindingMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.entity.FeishuAgentSessionBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuAgentSessionBindingService {

    private static final DateTimeFormatter TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String FEISHU_CHAT_SESSION_METADATA = "{\"channel\":\"FEISHU\",\"source\":\"feishu\"}";

    private final FeishuAgentSessionBindingMapper bindingMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final Clock clock = Clock.systemDefaultZone();

    public String getOrCreateActiveSession(String chatId, String chatType, String senderOpenId, String agentId) {
        requireText(chatId, "Feishu chat id is required");
        requireText(agentId, "Feishu default agent id is required");
        FeishuAgentSessionBinding existing = bindingMapper.selectByFeishuChatId(chatId);
        if (existing != null && StringUtils.hasText(existing.getSessionId())) {
            return existing.getSessionId();
        }
        return createNewSession(chatId, chatType, senderOpenId, agentId);
    }

    public String createNewSession(String chatId, String chatType, String senderOpenId, String agentId) {
        requireText(chatId, "Feishu chat id is required");
        requireText(agentId, "Feishu default agent id is required");
        LocalDateTime now = LocalDateTime.now(clock);
        String sessionId = UUID.randomUUID().toString();
        chatSessionMapper.insertWithId(ChatSession.builder()
                .id(sessionId)
                .agentId(agentId)
                .title(newSessionTitle(chatId, now))
                .metadata(FEISHU_CHAT_SESSION_METADATA)
                .createdAt(now)
                .updatedAt(now)
                .build());
        bindingMapper.upsertActiveSession(FeishuAgentSessionBinding.builder()
                .id(UUID.randomUUID().toString())
                .feishuChatId(chatId)
                .feishuChatType(emptyToNull(chatType))
                .feishuSenderOpenId(emptyToNull(senderOpenId))
                .agentId(agentId)
                .sessionId(sessionId)
                .createdAt(now)
                .updatedAt(now)
                .lastMessageAt(now)
                .build());
        log.info("Feishu agent active session created: chatKey={}, sessionId={}",
                safeChatKey(chatId), shortId(sessionId));
        return sessionId;
    }

    private String newSessionTitle(String chatId, LocalDateTime now) {
        return "Feishu Agent - " + safeChatKey(chatId) + " - " + TITLE_TIME_FORMATTER.format(now);
    }

    private String safeChatKey(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return "unknown";
        }
        return chatId.length() <= 6 ? chatId : chatId.substring(chatId.length() - 6);
    }

    private String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
