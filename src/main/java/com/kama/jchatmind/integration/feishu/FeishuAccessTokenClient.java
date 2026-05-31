package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class FeishuAccessTokenClient {

    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final long REFRESH_AHEAD_MILLIS = 5 * 60 * 1000L;

    private final RestTemplate restTemplate;
    private final FeishuProperties properties;
    private final Clock clock;

    private String cachedToken;
    private long expiresAtMillis;

    @Autowired
    public FeishuAccessTokenClient(RestTemplateBuilder builder, FeishuProperties properties) {
        this(builder.build(), properties, Clock.systemUTC());
    }

    FeishuAccessTokenClient(RestTemplate restTemplate, FeishuProperties properties, Clock clock) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized Optional<String> getTenantAccessToken() {
        long now = clock.millis();
        if (StringUtils.hasText(cachedToken) && now + REFRESH_AHEAD_MILLIS < expiresAtMillis) {
            return Optional.of(cachedToken);
        }
        if (!StringUtils.hasText(properties.getAppId()) || !StringUtils.hasText(properties.getAppSecret())) {
            log.warn("Feishu tenant access token skipped: appId or appSecret is not configured");
            return Optional.empty();
        }

        try {
            TokenResponse response = restTemplate.postForObject(
                    TOKEN_URL,
                    Map.of(
                            "app_id", properties.getAppId(),
                            "app_secret", properties.getAppSecret()
                    ),
                    TokenResponse.class);
            if (response == null || response.getCode() != 0 || !StringUtils.hasText(response.getTenantAccessToken())) {
                log.warn("Feishu tenant access token failed: code={}, msg={}",
                        response == null ? null : response.getCode(),
                        response == null ? null : response.getMsg());
                return Optional.empty();
            }

            cachedToken = response.getTenantAccessToken();
            expiresAtMillis = now + Math.max(0, response.getExpire()) * 1000L;
            return Optional.of(cachedToken);
        } catch (RestClientException e) {
            log.warn("Feishu tenant access token request failed: error={}", e.getMessage());
            return Optional.empty();
        }
    }

    @Data
    private static class TokenResponse {
        private int code;
        private String msg;
        @JsonProperty("tenant_access_token")
        private String tenantAccessToken;
        private long expire;
    }
}
