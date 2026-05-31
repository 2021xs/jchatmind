package com.kama.jchatmind.integration.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeishuCommandParserTest {

    private final FeishuCommandParser parser = new FeishuCommandParser();

    @Test
    void parsesHelpCommand() {
        FeishuCommandParser.ParsedCommand command = parser.parse("  /help  ");

        assertEquals(FeishuCommandParser.CommandType.HELP, command.type());
        assertNull(command.repoId());
        assertNull(command.query());
    }

    @Test
    void parsesAskCodeCommandAndKeepsChineseQuery() {
        FeishuCommandParser.ParsedCommand command = parser.parse("/ask-code hmdp 秒杀订单队列在哪里定义？");

        assertEquals(FeishuCommandParser.CommandType.ASK_CODE, command.type());
        assertEquals("hmdp", command.repoId());
        assertEquals("秒杀订单队列在哪里定义？", command.query());
    }

    @Test
    void askCodeWithoutRepoIdIsInvalid() {
        FeishuCommandParser.ParsedCommand command = parser.parse("/ask-code");

        assertEquals(FeishuCommandParser.CommandType.ASK_CODE_INVALID, command.type());
        assertNull(command.repoId());
        assertNull(command.query());
    }

    @Test
    void askCodeWithoutQueryIsInvalid() {
        FeishuCommandParser.ParsedCommand command = parser.parse("/ask-code hmdp");

        assertEquals(FeishuCommandParser.CommandType.ASK_CODE_INVALID, command.type());
        assertEquals("hmdp", command.repoId());
        assertNull(command.query());
    }

    @Test
    void ordinaryTextIsUnknown() {
        FeishuCommandParser.ParsedCommand command = parser.parse("hello");

        assertEquals(FeishuCommandParser.CommandType.UNKNOWN, command.type());
        assertNull(command.repoId());
        assertNull(command.query());
    }
}
