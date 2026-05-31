package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuBotService {

    private static final int MAX_EVIDENCE_COUNT = 5;
    private static final int MAX_SNIPPET_LENGTH = 120;
    private static final int MAX_REPLY_LENGTH = 3000;

    static final String HELP_TEXT = """
            JChatMind Feishu bot is connected.

            Currently supported:
            /help View help
            /ask-code <repoId> <question> Query code evidence with Code RAG
            """;

    static final String ASK_CODE_USAGE = """
            用法：
            /ask-code <repoId> <问题>

            示例：
            /ask-code hmdp 秒杀订单队列在哪里定义？
            """;

    static final String ASK_CODE_ERROR = "查询失败，请稍后重试；后端日志已记录错误。";

    private final FeishuCommandParser commandParser;
    private final FeishuMessageClient messageClient;
    private final CodeRagAnswerEvidenceService codeRagAnswerEvidenceService;

    public void handleTextMessage(String chatId, String text) {
        FeishuCommandParser.ParsedCommand command = commandParser.parse(text);
        switch (command.type()) {
            case HELP -> messageClient.sendText(chatId, HELP_TEXT);
            case ASK_CODE -> handleAskCode(chatId, command.repoId(), command.query());
            case ASK_CODE_INVALID -> messageClient.sendText(chatId, ASK_CODE_USAGE);
            case UNKNOWN -> log.info("Feishu text message ignored: command not supported");
        }
    }

    private void handleAskCode(String chatId, String repoId, String query) {
        long started = System.currentTimeMillis();
        try {
            CodeAnswerEvidenceResult result = codeRagAnswerEvidenceService.retrieve(repoId, query);
            long latencyMs = System.currentTimeMillis() - started;
            log.info("Feishu ask-code completed: repoId={}, queryLength={}, latencyMs={}",
                    repoId, query.length(), latencyMs);
            messageClient.sendText(chatId, formatAskCodeReply(query, result));
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - started;
            log.warn("Feishu ask-code failed: repoId={}, queryLength={}, latencyMs={}, error={}",
                    repoId, query.length(), latencyMs, e.getMessage());
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
                sb.append("   snippet: ").append(truncate(valueOrDash(item.getContentPreview()), MAX_SNIPPET_LENGTH))
                        .append('\n');
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
}
