package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
public class FeishuMessageClient {

    private static final String SEND_MESSAGE_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final FeishuAccessTokenClient accessTokenClient;

    @Autowired
    public FeishuMessageClient(RestTemplateBuilder builder,
                               ObjectMapper objectMapper,
                               FeishuAccessTokenClient accessTokenClient) {
        this(builder.build(), objectMapper, accessTokenClient);
    }

    FeishuMessageClient(RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        FeishuAccessTokenClient accessTokenClient) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.accessTokenClient = accessTokenClient;
    }

    public boolean sendText(String chatId, String text) {
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(text)) {
            log.warn("Feishu text message skipped: chatId or text is empty");
            return false;
        }

        return accessTokenClient.getTenantAccessToken()
                .map(token -> doSendText(token, chatId, text))
                .orElse(false);
    }

    private boolean doSendText(String token, String chatId, String text) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            MessageResponse response = restTemplate.postForObject(
                    SEND_MESSAGE_URL,
                    new HttpEntity<>(Map.of(
                            "receive_id", chatId,
                            "msg_type", "text",
                            "content", objectMapper.writeValueAsString(Map.of("text", text))
                    ), headers),
                    MessageResponse.class);
            if (response == null || response.getCode() != 0) {
                log.warn("Feishu text message send failed: chatId={}, code={}, msg={}",
                        chatId,
                        response == null ? null : response.getCode(),
                        response == null ? null : response.getMsg());
                return false;
            }
            log.info("Feishu text message sent: chatId={}", chatId);
            return true;
        } catch (JsonProcessingException e) {
            log.warn("Feishu text message content serialization failed: chatId={}, error={}", chatId, e.getOriginalMessage());
            return false;
        } catch (HttpStatusCodeException e) {
            MessageResponse response = parseMessageResponse(e.getResponseBodyAsString());
            log.warn("Feishu text message request failed: chatId={}, status={}, code={}, msg={}",
                    chatId, e.getStatusCode().value(),
                    response == null ? null : response.getCode(),
                    response == null ? null : response.getMsg());
            return false;
        } catch (RestClientException e) {
            log.warn("Feishu text message request failed: chatId={}, error={}", chatId, e.getMessage());
            return false;
        }
    }

    private MessageResponse parseMessageResponse(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return objectMapper.readValue(body, MessageResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Data
    private static class MessageResponse {
        private int code;
        private String msg;
    }
}
