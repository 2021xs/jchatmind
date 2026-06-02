package com.kama.jchatmind.integration.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class FeishuAgentCommandService {

    static final String AGENT_USAGE = """
            用法:
            /agent <问题>

            示例:
            /agent 分析秒杀订单链路
            """;
    static final String AGENT_SEND_ERROR = "Agent 卡片发送失败，请查看后端日志。";
    static final String AGENT_MISSING_CONFIG_ERROR = "未配置飞书默认 Agent，请设置 JCHATMIND_FEISHU_DEFAULT_AGENT_ID。";
    static final String NEW_SESSION_CREATED_TEXT = """
            已创建新的 Agent 会话。
            后续 /agent 将使用这个新会话。
            """;

    private static final int MAX_QUESTION_LENGTH = 300;
    private static final int MAX_RESULT_LENGTH = 3000;
    private static final int MAX_FOLLOWUP_MESSAGE_LENGTH = 3500;
    private static final DateTimeFormatter CARD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FeishuProperties properties;
    private final FeishuMessageClient messageClient;
    private final FeishuCardMessageClient cardMessageClient;
    private final FeishuAgentRunAdapter agentRunAdapter;
    private final FeishuAgentSessionBindingService sessionBindingService;
    private final Executor taskExecutor;
    private final Clock clock;

    @Autowired
    public FeishuAgentCommandService(FeishuProperties properties,
                                     FeishuMessageClient messageClient,
                                     FeishuCardMessageClient cardMessageClient,
                                     FeishuAgentRunAdapter agentRunAdapter,
                                     FeishuAgentSessionBindingService sessionBindingService,
                                     Executor taskExecutor) {
        this(properties, messageClient, cardMessageClient, agentRunAdapter, sessionBindingService,
                taskExecutor, Clock.systemDefaultZone());
    }

    FeishuAgentCommandService(FeishuProperties properties,
                              FeishuMessageClient messageClient,
                              FeishuCardMessageClient cardMessageClient,
                              FeishuAgentRunAdapter agentRunAdapter,
                              FeishuAgentSessionBindingService sessionBindingService,
                              Executor taskExecutor,
                              Clock clock) {
        this.properties = properties;
        this.messageClient = messageClient;
        this.cardMessageClient = cardMessageClient;
        this.agentRunAdapter = agentRunAdapter;
        this.sessionBindingService = sessionBindingService;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
    }

    public void handleAgent(String chatId, String chatType, String senderOpenId, String question) {
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
            taskExecutor.execute(() -> runAgentAndUpdateCard(messageId, taskId, agentId,
                    chatId, chatType, senderOpenId, question));
        } catch (RuntimeException e) {
            log.warn("Feishu agent card send failed: taskId={}, questionLength={}, error={}",
                    taskId, question.length(), e.getMessage());
            messageClient.sendText(chatId, AGENT_SEND_ERROR);
        }
    }

    public void handleNewSession(String chatId, String chatType, String senderOpenId) {
        String agentId = properties.getDefaultAgentId();
        if (!StringUtils.hasText(agentId)) {
            messageClient.sendText(chatId, AGENT_MISSING_CONFIG_ERROR);
            return;
        }
        String sessionId = sessionBindingService.createNewSession(chatId, chatType, senderOpenId, agentId);
        log.info("Feishu agent new session command completed: sessionId={}", shortId(sessionId));
        messageClient.sendText(chatId, NEW_SESSION_CREATED_TEXT);
    }

    private void runAgentAndUpdateCard(String messageId,
                                       String taskId,
                                       String agentId,
                                       String chatId,
                                       String chatType,
                                       String senderOpenId,
                                       String question) {
        long startedAt = System.currentTimeMillis();
        try {
            FeishuAgentRunAdapter.AgentRunResult result =
                    agentRunAdapter.run(agentId, chatId, chatType, senderOpenId, question);
            long latencyMs = System.currentTimeMillis() - startedAt;
            log.info("Feishu agent command completed: taskId={}, sessionId={}, userMessageId={}, latencyMs={}",
                    taskId, result.sessionId(), result.userMessageId(), latencyMs);
            FeishuAgentCardSnapshot finished = FeishuAgentCardSnapshot.builder()
                    .taskId(taskId)
                    .question(truncate(question, MAX_QUESTION_LENGTH))
                    .status("已完成")
                    .stage("Agent 执行完成")
                    .result(cardResult(result.answer()))
                    .updatedAt(nowText())
                    .build();
            cardMessageClient.updateAgentCard(messageId, finished);
            sendFullAnswerIfLong(chatId, result.answer());
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            log.warn("Feishu agent command failed: taskId={}, questionLength={}, latencyMs={}, error={}",
                    taskId, question.length(), latencyMs, safeErrorMessage(e), e);
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
        Throwable cursor = e;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        if (StringUtils.hasText(cursor.getMessage())) {
            return cursor.getClass().getSimpleName() + ": " + cursor.getMessage();
        }
        if (StringUtils.hasText(e.getMessage())) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    private void sendFullAnswerIfLong(String chatId, String answer) {
        String text = answer == null ? "" : answer;
        if (text.length() <= MAX_RESULT_LENGTH) {
            return;
        }
        List<String> parts = splitText(text, MAX_FOLLOWUP_MESSAGE_LENGTH);
        for (int i = 0; i < parts.size(); i++) {
            String message = "Full answer " + (i + 1) + "/" + parts.size() + "\n\n" + parts.get(i);
            boolean sent = messageClient.sendText(chatId, message);
            if (!sent) {
                log.warn("Feishu long agent answer part send failed: chatId={}, part={}/{}",
                        chatId, i + 1, parts.size());
            }
        }
    }

    private List<String> splitText(String text, int maxLength) {
        List<String> parts = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + maxLength);
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline > offset + maxLength / 2) {
                    end = newline + 1;
                }
            }
            parts.add(text.substring(offset, end));
            offset = end;
        }
        return parts;
    }

    private String cardResult(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= MAX_RESULT_LENGTH) {
            return text;
        }
        String marker = "\n\nFull answer is long; complete answer is sent below in parts.";
        int keep = Math.max(0, MAX_RESULT_LENGTH - marker.length());
        return text.substring(0, keep) + marker;
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

    private String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    private String nowText() {
        return LocalDateTime.now(clock).format(CARD_TIME_FORMATTER);
    }
}
