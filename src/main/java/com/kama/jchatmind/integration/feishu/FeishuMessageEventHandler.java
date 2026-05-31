package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuMessageEventHandler {

    static final String MESSAGE_RECEIVE_EVENT = "im.message.receive_v1";

    private final ObjectMapper objectMapper;

    public void handle(String eventType, JsonNode root) {
        if (!MESSAGE_RECEIVE_EVENT.equals(eventType)) {
            return;
        }

        JsonNode message = root.path("event").path("message");
        String messageId = message.path("message_id").asText("");
        String chatId = message.path("chat_id").asText("");
        String chatType = message.path("chat_type").asText("");
        String messageType = message.path("message_type").asText("");
        String content = message.path("content").asText("");

        if (!"text".equals(messageType)) {
            log.info("Received Feishu non-text message event: messageId={}, chatId={}, chatType={}, messageType={}",
                    messageId, chatId, chatType, messageType);
            return;
        }

        String text = parseTextContent(messageId, content);
        log.info("Received Feishu message event: messageId={}, chatId={}, chatType={}, messageType={}, text={}",
                messageId, chatId, chatType, messageType, text);
    }

    private String parseTextContent(String messageId, String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        try {
            JsonNode contentRoot = objectMapper.readTree(content);
            return contentRoot.path("text").asText("");
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Feishu text message content: messageId={}, error={}",
                    messageId, e.getOriginalMessage());
            return "";
        }
    }
}
