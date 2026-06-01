package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FeishuAgentCardRenderer {

    private static final int MAX_QUESTION_LENGTH = 300;
    private static final int MAX_RESULT_LENGTH = 1000;

    private final ObjectMapper objectMapper;

    public String renderCardContent(FeishuAgentCardSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(buildCard(snapshot));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to render Feishu agent card", e);
        }
    }

    private Map<String, Object> buildCard(FeishuAgentCardSnapshot snapshot) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdownLine("**状态：**" + value(snapshot.getStatus())));
        elements.add(markdownLine("**问题：**" + truncate(value(snapshot.getQuestion()), MAX_QUESTION_LENGTH)));
        elements.add(markdownLine("**任务ID：**" + value(snapshot.getTaskId())));
        elements.add(markdownLine("**当前阶段：**" + value(snapshot.getStage())));
        if (StringUtils.hasText(snapshot.getResult())) {
            elements.add(markdownLine("**结果：**" + truncate(snapshot.getResult(), MAX_RESULT_LENGTH)));
        }
        elements.add(markdownLine("**更新时间：**" + value(snapshot.getUpdatedAt())));

        return Map.of(
                "config", Map.of("update_multi", true),
                "header", Map.of(
                        "template", template(snapshot.getStatus()),
                        "title", Map.of("tag", "plain_text", "content", "JChatMind Agent 任务")
                ),
                "elements", elements
        );
    }

    private Map<String, Object> markdownLine(String content) {
        return Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content", content)
        );
    }

    private String template(String status) {
        if ("已完成".equals(status)) {
            return "green";
        }
        if ("失败".equals(status)) {
            return "red";
        }
        return "blue";
    }

    private String value(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
