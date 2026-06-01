package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
public class FeishuCardMessageClient {

    private static final String SEND_MESSAGE_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id";
    private static final String UPDATE_MESSAGE_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages/{messageId}";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final FeishuAccessTokenClient accessTokenClient;
    private final FeishuAgentCardRenderer cardRenderer;

    @Autowired
    public FeishuCardMessageClient(RestTemplateBuilder builder,
                                   ObjectMapper objectMapper,
                                   FeishuAccessTokenClient accessTokenClient,
                                   FeishuAgentCardRenderer cardRenderer) {
        this(builder.build(), objectMapper, accessTokenClient, cardRenderer);
    }

    FeishuCardMessageClient(RestTemplate restTemplate,
                            ObjectMapper objectMapper,
                            FeishuAccessTokenClient accessTokenClient,
                            FeishuAgentCardRenderer cardRenderer) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.accessTokenClient = accessTokenClient;
        this.cardRenderer = cardRenderer;
    }

    public String sendAgentCard(String chatId, FeishuAgentCardSnapshot snapshot) {
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId is required");
        }
        String token = accessTokenClient.getTenantAccessToken()
                .orElseThrow(() -> new IllegalStateException("Feishu tenant access token is unavailable"));
        String content = cardRenderer.renderCardContent(snapshot);
        try {
            MessageResponse response = restTemplate.postForObject(
                    SEND_MESSAGE_URL,
                    new HttpEntity<>(Map.of(
                            "receive_id", chatId,
                            "msg_type", "interactive",
                            "content", content
                    ), buildHeaders(token)),
                    MessageResponse.class);
            if (response == null || response.getCode() != 0 || response.getData() == null
                    || !StringUtils.hasText(response.getData().getMessageId())) {
                throw new IllegalStateException("Feishu agent card send failed: code="
                        + (response == null ? null : response.getCode())
                        + ", msg=" + (response == null ? null : response.getMsg()));
            }
            log.info("Feishu agent card sent: chatId={}, messageId={}", chatId, response.getData().getMessageId());
            return response.getData().getMessageId();
        } catch (HttpStatusCodeException e) {
            MessageResponse response = parseMessageResponse(e.getResponseBodyAsString());
            throw new IllegalStateException("Feishu agent card send request failed: status=" + e.getStatusCode().value()
                    + ", code=" + (response == null ? null : response.getCode())
                    + ", msg=" + (response == null ? null : response.getMsg()), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("Feishu agent card send request failed: " + e.getMessage(), e);
        }
    }

    public void updateAgentCard(String messageId, FeishuAgentCardSnapshot snapshot) {
        if (!StringUtils.hasText(messageId)) {
            throw new IllegalArgumentException("messageId is required");
        }
        String token = accessTokenClient.getTenantAccessToken()
                .orElseThrow(() -> new IllegalStateException("Feishu tenant access token is unavailable"));
        String content = cardRenderer.renderCardContent(snapshot);
        try {
            ResponseEntity<MessageResponse> entity = restTemplate.exchange(
                    UPDATE_MESSAGE_URL,
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("content", content), buildHeaders(token)),
                    MessageResponse.class,
                    messageId);
            MessageResponse response = entity.getBody();
            if (response == null || response.getCode() != 0) {
                throw new IllegalStateException("Feishu agent card update failed: code="
                        + (response == null ? null : response.getCode())
                        + ", msg=" + (response == null ? null : response.getMsg()));
            }
            log.info("Feishu agent card updated: messageId={}", messageId);
        } catch (HttpStatusCodeException e) {
            MessageResponse response = parseMessageResponse(e.getResponseBodyAsString());
            throw new IllegalStateException("Feishu agent card update request failed: status=" + e.getStatusCode().value()
                    + ", code=" + (response == null ? null : response.getCode())
                    + ", msg=" + (response == null ? null : response.getMsg()), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("Feishu agent card update request failed: " + e.getMessage(), e);
        }
    }

    private HttpHeaders buildHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
        private MessageData data;
    }

    @Data
    private static class MessageData {
        @JsonProperty("message_id")
        private String messageId;
    }
}
