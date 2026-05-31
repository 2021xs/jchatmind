package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/feishu/events")
@RequiredArgsConstructor
public class FeishuEventController {

    private static final String URL_VERIFICATION = "url_verification";

    private final ObjectMapper objectMapper;
    private final FeishuProperties feishuProperties;

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleEvent(@RequestBody String body) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(body);
        String type = root.path("type").asText(null);
        String eventType = StringUtils.hasText(type)
                ? type
                : root.path("header").path("event_type").asText("unknown");
        log.info("Received Feishu event type={}", eventType);

        if (URL_VERIFICATION.equals(type)) {
            if (!isValidToken(root.path("token").asText(null))) {
                log.warn("Feishu URL verification token validation failed");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String challenge = root.path("challenge").asText();
            log.info("Feishu URL verification succeeded");
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        return ResponseEntity.ok(Map.of("code", 0));
    }

    private boolean isValidToken(String requestToken) {
        String configuredToken = feishuProperties.getVerificationToken();
        return StringUtils.hasText(configuredToken) && Objects.equals(requestToken, configuredToken);
    }
}
