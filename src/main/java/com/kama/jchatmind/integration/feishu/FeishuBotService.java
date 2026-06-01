package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class FeishuBotService {

    private static final int MAX_EVIDENCE_COUNT = 5;
    private static final int MAX_SNIPPET_LENGTH = 1000;
    private static final int MAX_REPLY_LENGTH = 12000;
    private static final long AGENT_TEST_UPDATE_DELAY_MILLIS = 2000L;
    private static final DateTimeFormatter CARD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final String HELP_TEXT = """
            JChatMind Feishu bot is connected.

            Currently supported:
            /help View help
            /ask-code <repoKey> <question> Query code evidence with Code RAG
            /agent <question> Run the configured JChatMind Agent
            /new-session Start a fresh Feishu Agent session
            /agent-test <question> Verify Feishu card update
            """;

    static final String ASK_CODE_USAGE = """
            用法：
            /ask-code <仓库名或别名> <问题>

            示例：
            /ask-code 黑马点评 秒杀订单队列在哪里定义？
            """;

    static final String ASK_CODE_ERROR = "查询失败，请稍后重试；后端日志已记录错误。";
    static final String ASK_CODE_REPO_NOT_FOUND = "未找到该代码仓库，请检查仓库名或别名配置。";
    static final String AGENT_TEST_USAGE = """
            用法：
            /agent-test <问题>

            示例：
            /agent-test 分析秒杀订单链路
            """;
    static final String AGENT_TEST_SEND_ERROR = "Agent 测试卡片发送失败，请查看后端日志。";

    private final FeishuCommandParser commandParser;
    private final FeishuMessageClient messageClient;
    private final CodeRagAnswerEvidenceService codeRagAnswerEvidenceService;
    private final FeishuRepoResolver repoResolver;
    private final FeishuCardMessageClient cardMessageClient;
    private final FeishuAgentCommandService agentCommandService;
    private final Executor taskExecutor;
    private final Clock clock;
    private final long agentTestUpdateDelayMillis;

    @Autowired
    public FeishuBotService(FeishuCommandParser commandParser,
                            FeishuMessageClient messageClient,
                            CodeRagAnswerEvidenceService codeRagAnswerEvidenceService,
                            FeishuRepoResolver repoResolver,
                            FeishuCardMessageClient cardMessageClient,
                            FeishuAgentCommandService agentCommandService,
                            Executor taskExecutor) {
        this(commandParser, messageClient, codeRagAnswerEvidenceService, repoResolver, cardMessageClient, agentCommandService,
                taskExecutor, Clock.systemDefaultZone(), AGENT_TEST_UPDATE_DELAY_MILLIS);
    }

    FeishuBotService(FeishuCommandParser commandParser,
                     FeishuMessageClient messageClient,
                     CodeRagAnswerEvidenceService codeRagAnswerEvidenceService,
                     FeishuRepoResolver repoResolver,
                     FeishuCardMessageClient cardMessageClient,
                     FeishuAgentCommandService agentCommandService,
                     Executor taskExecutor,
                     Clock clock,
                     long agentTestUpdateDelayMillis) {
        this.commandParser = commandParser;
        this.messageClient = messageClient;
        this.codeRagAnswerEvidenceService = codeRagAnswerEvidenceService;
        this.repoResolver = repoResolver;
        this.cardMessageClient = cardMessageClient;
        this.agentCommandService = agentCommandService;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
        this.agentTestUpdateDelayMillis = agentTestUpdateDelayMillis;
    }

    public void handleTextMessage(String chatId, String chatType, String senderOpenId, String text) {
        FeishuCommandParser.ParsedCommand command = commandParser.parse(text);
        switch (command.type()) {
            case HELP -> messageClient.sendText(chatId, HELP_TEXT);
            case ASK_CODE -> handleAskCode(chatId, command.repoId(), command.query());
            case ASK_CODE_INVALID -> messageClient.sendText(chatId, ASK_CODE_USAGE);
            case AGENT_TEST -> handleAgentTest(chatId, command.query());
            case AGENT_TEST_INVALID -> messageClient.sendText(chatId, AGENT_TEST_USAGE);
            case NEW_SESSION -> agentCommandService.handleNewSession(chatId, chatType, senderOpenId);
            case AGENT -> agentCommandService.handleAgent(chatId, chatType, senderOpenId, command.query());
            case AGENT_INVALID -> messageClient.sendText(chatId, FeishuAgentCommandService.AGENT_USAGE);
            case UNKNOWN -> log.info("Feishu text message ignored: command not supported");
        }
    }

    void handleTextMessage(String chatId, String text) {
        handleTextMessage(chatId, "", "", text);
    }

    private void handleAgentTest(String chatId, String question) {
        String taskId = newTaskId();
        FeishuAgentCardSnapshot running = FeishuAgentCardSnapshot.builder()
                .taskId(taskId)
                .question(question)
                .status("处理中")
                .stage("已收到任务，正在模拟执行")
                .updatedAt(nowText())
                .build();
        try {
            String messageId = cardMessageClient.sendAgentCard(chatId, running);
            taskExecutor.execute(() -> updateAgentTestCardLater(messageId, taskId, question));
        } catch (RuntimeException e) {
            log.warn("Feishu agent-test card send failed: taskId={}, questionLength={}, error={}",
                    taskId, question.length(), e.getMessage());
            messageClient.sendText(chatId, AGENT_TEST_SEND_ERROR);
        }
    }

    private void updateAgentTestCardLater(String messageId, String taskId, String question) {
        try {
            if (agentTestUpdateDelayMillis > 0) {
                Thread.sleep(agentTestUpdateDelayMillis);
            }
            FeishuAgentCardSnapshot finished = FeishuAgentCardSnapshot.builder()
                    .taskId(taskId)
                    .question(question)
                    .status("已完成")
                    .stage("模拟执行完成")
                    .result("这是 /agent-test 的卡片更新验证结果，尚未接入真实 Agent")
                    .updatedAt(nowText())
                    .build();
            cardMessageClient.updateAgentCard(messageId, finished);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Feishu agent-test card update interrupted: taskId={}", taskId);
        } catch (RuntimeException e) {
            log.warn("Feishu agent-test card update failed: taskId={}, messageId={}, error={}",
                    taskId, messageId, e.getMessage());
        }
    }

    private void handleAskCode(String chatId, String repoKey, String query) {
        String repoId = repoResolver.resolveRepoId(repoKey).orElse(null);
        if (!StringUtils.hasText(repoId)) {
            log.info("Feishu ask-code repo alias not found: repoKey={}, queryLength={}", repoKey, query.length());
            messageClient.sendText(chatId, ASK_CODE_REPO_NOT_FOUND);
            return;
        }

        long started = System.currentTimeMillis();
        try {
            CodeAnswerEvidenceResult result = codeRagAnswerEvidenceService.retrieve(repoId, query);
            long latencyMs = System.currentTimeMillis() - started;
            log.info("Feishu ask-code completed: repoKey={}, repoId={}, queryLength={}, latencyMs={}",
                    repoKey, repoId, query.length(), latencyMs);
            messageClient.sendText(chatId, formatAskCodeReply(query, result));
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - started;
            log.warn("Feishu ask-code failed: repoKey={}, repoId={}, queryLength={}, latencyMs={}, error={}",
                    repoKey, repoId, query.length(), latencyMs, e.getMessage());
            messageClient.sendText(chatId, ASK_CODE_ERROR);
        }
    }

    private String formatAskCodeReply(String query, CodeAnswerEvidenceResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：\n").append(truncate(query, 400)).append("\n\n");
        sb.append("命中证据：\n");

        List<CodeSearchResult> evidence = result == null ? List.of() : result.getSelectedEvidence();
        if (evidence == null || evidence.isEmpty()) {
            sb.append("未找到相关代码证据。\n");
        } else {
            int count = Math.min(MAX_EVIDENCE_COUNT, evidence.size());
            for (int i = 0; i < count; i++) {
                CodeSearchResult item = evidence.get(i);
                sb.append(i + 1).append(". ").append(valueOrDash(item.getFilePath())).append('\n');
                String lineRange = formatLineRange(item);
                if (StringUtils.hasText(lineRange)) {
                    sb.append("   line: ").append(lineRange).append('\n');
                }
                sb.append("   symbol: ").append(valueOrDash(item.getSymbolName())).append('\n');
                sb.append("   snippet:\n").append(indent(truncate(valueOrDash(item.getContentPreview()), MAX_SNIPPET_LENGTH)))
                        .append("\n\n");
            }
        }

        sb.append("\n说明：\n");
        sb.append("结果来自 JChatMind Code RAG evidence 查询。");
        return truncate(sb.toString(), MAX_REPLY_LENGTH);
    }

    private String formatLineRange(CodeSearchResult item) {
        if (item.getStartLine() == null) {
            return "";
        }
        if (item.getEndLine() == null) {
            return String.valueOf(item.getStartLine());
        }
        return item.getStartLine() + "-" + item.getEndLine();
    }

    private String valueOrDash(String value) {
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

    private String indent(String value) {
        if (!StringUtils.hasText(value)) {
            return "      -";
        }
        return "      " + value.replace("\n", "\n      ");
    }

    private String newTaskId() {
        return "FT-" + clock.millis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String nowText() {
        return LocalDateTime.now(clock).format(CARD_TIME_FORMATTER);
    }
}
