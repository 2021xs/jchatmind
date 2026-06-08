package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class FeishuMessageEventHandler {

    static final String MESSAGE_RECEIVE_EVENT = "im.message.receive_v1";
    private static final long MESSAGE_ID_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final long MESSAGE_FINGERPRINT_CACHE_TTL_MS = 60 * 1000L;

    private final ObjectMapper objectMapper;
    private final FeishuBotService botService;
    private final Executor taskExecutor;
    private final Map<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    private final Map<String, Long> processedMessageFingerprints = new ConcurrentHashMap<>();

    public FeishuMessageEventHandler(ObjectMapper objectMapper,
                                     FeishuBotService botService,
                                     @Qualifier("taskExecutor") Executor taskExecutor) {
        this.objectMapper = objectMapper;
        this.botService = botService;
        this.taskExecutor = taskExecutor;
    }

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
        String senderOpenId = root.path("event").path("sender").path("sender_id").path("open_id").asText("");

        if (!"text".equals(messageType)) {
            log.info("Received Feishu non-text message event: messageId={}, chatId={}, chatType={}, messageType={}",
                    messageId, chatId, chatType, messageType);
            return;
        }
        if (isDuplicate(messageId)) {
            log.info("Duplicate Feishu message event ignored: messageId={}", messageId);
            return;
        }

        String text = parseTextContent(messageId, content);
        log.info("Received Feishu message event: messageId={}, chatId={}, chatType={}, messageType={}, textLength={}",
                messageId, chatId, chatType, messageType, text.length());
        if (isDuplicateMessageFingerprint(chatId, text)) {
            log.info("Duplicate Feishu message text ignored: messageId={}, chatId={}, textLength={}",
                    messageId, chatId, text.length());
            return;
        }
        taskExecutor.execute(() -> handleTextMessage(messageId, chatId, chatType, senderOpenId, text));
    }

    private void handleTextMessage(String messageId, String chatId, String chatType, String senderOpenId, String text) {
        try {
            botService.handleTextMessage(chatId, chatType, senderOpenId, text);
        } catch (RuntimeException e) {
            log.warn("Feishu text message handling failed: messageId={}, error={}", messageId, e.getMessage());
        }
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

    private boolean isDuplicate(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanupProcessedMessageIds(now);
        return processedMessageIds.putIfAbsent(messageId, now) != null;
    }

    private void cleanupProcessedMessageIds(long now) {
        processedMessageIds.entrySet().removeIf(entry -> now - entry.getValue() > MESSAGE_ID_CACHE_TTL_MS);
    }

    private boolean isDuplicateMessageFingerprint(String chatId, String text) {
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(text)) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanupProcessedMessageFingerprints(now);
        String fingerprint = chatId + '\n' + text.trim();
        return processedMessageFingerprints.putIfAbsent(fingerprint, now) != null;
    }

    private void cleanupProcessedMessageFingerprints(long now) {
        processedMessageFingerprints.entrySet()
                .removeIf(entry -> now - entry.getValue() > MESSAGE_FINGERPRINT_CACHE_TTL_MS);
    }
}
