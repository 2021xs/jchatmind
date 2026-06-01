package com.kama.jchatmind.integration.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class FeishuAgentCommandService {

    static final String AGENT_USAGE = """
            用法：
            /agent <问题>

            示例：
            /agent 分析秒杀订单链路
            """;
    static final String AGENT_SEND_ERROR = "Agent 卡片发送失败，请查看后端日志。";
    static final String AGENT_MISSING_CONFIG_ERROR = "未配置飞书默认 Agent，请设置 JCHATMIND_FEISHU_DEFAULT_AGENT_ID。";

    private static final int MAX_QUESTION_LENGTH = 300;
    private static final int MAX_RESULT_LENGTH = 3000;
    private static final DateTimeFormatter CARD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FeishuProperties properties;
    private final FeishuMessageClient messageClient;
    private final FeishuCardMessageClient cardMessageClient;
    private final FeishuAgentRunAdapter agentRunAdapter;
    private final Executor taskExecutor;
    private final Clock clock;

    public FeishuAgentCommandService(FeishuProperties properties,
                                     FeishuMessageClient messageClient,
                                     FeishuCardMessageClient cardMessageClient,
                                     FeishuAgentRunAdapter agentRunAdapter,
                                     Executor taskExecutor) {
        this(properties, messageClient, cardMessageClient, agentRunAdapter, taskExecutor, Clock.systemDefaultZone());
    }

    FeishuAgentCommandService(FeishuProperties properties,
                              FeishuMessageClient messageClient,
                              FeishuCardMessageClient cardMessageClient,
                              FeishuAgentRunAdapter agentRunAdapter,
                              Executor taskExecutor,
                              Clock clock) {
        this.properties = properties;
        this.messageClient = messageClient;
        this.cardMessageClient = cardMessageClient;
        this.agentRunAdapter = agentRunAdapter;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
    }

    public void handleAgent(String chatId, String question) {
        if (!StringUtils.hasText(question)) {
            messageClient.sendText(chatId, AGENT_USAGE);
            return;
        }
        String agentId = properties.getDefaultAgentId();
        if (!StringUtils.hasText(agentId)) {
            messageClient.sendText(chatId, AGENT_MISSING_CONFIG_ERROR);
            return;
        }

        String taskId = newTaskId();
        FeishuAgentCardSnapshot running = FeishuAgentCardSnapshot.builder()
                .taskId(taskId)
                .question(truncate(question, MAX_QUESTION_LENGTH))
                .status("处理中")
                .stage("Agent 处理中")
                .updatedAt(nowText())
                .build();
        try {
            String messageId = cardMessageClient.sendAgentCard(chatId, running);
            taskExecutor.execute(() -> runAgentAndUpdateCard(messageId, taskId, agentId, chatId, question));
        } catch (RuntimeException e) {
            log.warn("Feishu agent card send failed: taskId={}, questionLength={}, error={}",
                    taskId, question.length(), e.getMessage());
            messageClient.sendText(chatId, AGENT_SEND_ERROR);
        }
    }

    private void runAgentAndUpdateCard(String messageId, String taskId, String agentId, String chatId, String question) {
        long startedAt = System.currentTimeMillis();
        try {
            FeishuAgentRunAdapter.AgentRunResult result = agentRunAdapter.run(agentId, chatId, question);
            long latencyMs = System.currentTimeMillis() - startedAt;
            log.info("Feishu agent command completed: taskId={}, sessionId={}, userMessageId={}, latencyMs={}",
                    taskId, result.sessionId(), result.userMessageId(), latencyMs);
            FeishuAgentCardSnapshot finished = FeishuAgentCardSnapshot.builder()
                    .taskId(taskId)
                    .question(truncate(question, MAX_QUESTION_LENGTH))
                    .status("已完成")
                    .stage("Agent 执行完成")
                    .result(truncateWithMarker(result.answer(), MAX_RESULT_LENGTH))
                    .updatedAt(nowText())
                    .build();
            cardMessageClient.updateAgentCard(messageId, finished);
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            log.warn("Feishu agent command failed: taskId={}, questionLength={}, latencyMs={}, error={}",
                    taskId, question.length(), latencyMs, e.getMessage());
            FeishuAgentCardSnapshot failed = FeishuAgentCardSnapshot.builder()
                    .taskId(taskId)
                    .question(truncate(question, MAX_QUESTION_LENGTH))
                    .status("失败")
                    .stage("Agent 执行失败")
                    .result(truncateWithMarker(safeErrorMessage(e), MAX_RESULT_LENGTH))
                    .updatedAt(nowText())
                    .build();
            try {
                cardMessageClient.updateAgentCard(messageId, failed);
            } catch (RuntimeException updateError) {
                log.warn("Feishu agent failure card update failed: taskId={}, messageId={}, error={}",
                        taskId, messageId, updateError.getMessage());
            }
        }
    }

    private String safeErrorMessage(RuntimeException e) {
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
    }

    private String truncateWithMarker(String value, int maxLength) {
        String text = value == null ? "" : value;
        if (text.length() <= maxLength) {
            return text;
        }
        String marker = "\n...[truncated]";
        return text.substring(0, Math.max(0, maxLength - marker.length())) + marker;
    }

    private String truncate(String value, int maxLength) {
        String text = value == null ? "" : value;
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, maxLength);
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private String newTaskId() {
        return "FA-" + clock.millis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String nowText() {
        return LocalDateTime.now(clock).format(CARD_TIME_FORMATTER);
    }
}
