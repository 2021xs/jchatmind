package com.kama.jchatmind.integration.feishu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuBotService {

    static final String HELP_TEXT = """
            JChatMind Feishu bot is connected.

            Currently supported:
            /help View help

            Coming soon:
            /ask-code <repoId> <question> Query code evidence with Code RAG
            /db <SQL> Safe read-only SQL query
            """;

    private final FeishuCommandParser commandParser;
    private final FeishuMessageClient messageClient;

    public void handleTextMessage(String chatId, String text) {
        if (!commandParser.isHelpCommand(text)) {
            log.info("Feishu text message ignored: command not supported");
            return;
        }
        messageClient.sendText(chatId, HELP_TEXT);
    }
}
