package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuAgentCardRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeishuAgentCardRenderer renderer = new FeishuAgentCardRenderer(objectMapper);

    @Test
    void rendersRunningSnapshotAsValidJson() throws Exception {
        String content = renderer.renderCardContent(FeishuAgentCardSnapshot.builder()
                .taskId("FA-1")
                .question("分析秒杀订单链路")
                .status("处理中")
                .stage("Agent 处理中")
                .updatedAt("2026-06-01 11:00:00")
                .build());

        JsonNode root = objectMapper.readTree(content);
        assertTrue(root.path("config").path("update_multi").asBoolean());
        assertEquals("JChatMind Agent 任务", root.path("header").path("title").path("content").asText());
        assertEquals("blue", root.path("header").path("template").asText());
        assertTrue(content.contains("处理中"));
        assertTrue(content.contains("分析秒杀订单链路"));
    }

    @Test
    void rendersFinishedSnapshotAsValidJson() throws Exception {
        String content = renderer.renderCardContent(FeishuAgentCardSnapshot.builder()
                .taskId("FA-1")
                .question("分析秒杀订单链路")
                .status("已完成")
                .stage("Agent 执行完成")
                .result("这是 /agent 的真实执行结果")
                .updatedAt("2026-06-01 11:00:02")
                .build());

        JsonNode root = objectMapper.readTree(content);
        assertEquals("green", root.path("header").path("template").asText());
        assertTrue(content.contains("已完成"));
        assertTrue(content.contains("真实执行结果"));
    }

    @Test
    void rendersFailedSnapshotAsValidJson() throws Exception {
        String content = renderer.renderCardContent(FeishuAgentCardSnapshot.builder()
                .taskId("FA-1")
                .question("分析秒杀订单链路")
                .status("失败")
                .stage("Agent 执行失败")
                .result("模型不可用")
                .updatedAt("2026-06-01 11:00:02")
                .build());

        JsonNode root = objectMapper.readTree(content);
        assertEquals("red", root.path("header").path("template").asText());
        assertTrue(content.contains("失败"));
    }

    @Test
    void truncatesLongQuestionAndResult() {
        String content = renderer.renderCardContent(FeishuAgentCardSnapshot.builder()
                .taskId("FA-1")
                .question("问".repeat(500))
                .status("已完成")
                .stage("Agent 执行完成")
                .result("结".repeat(3200))
                .updatedAt("2026-06-01 11:00:02")
                .build());

        assertTrue(content.contains("问".repeat(297) + "..."));
        assertTrue(content.contains("结".repeat(2997) + "..."));
    }
}
