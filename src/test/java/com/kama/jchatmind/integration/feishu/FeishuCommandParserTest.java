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
    void parsesAgentTestCommandAndKeepsChineseQuestion() {
        FeishuCommandParser.ParsedCommand command = parser.parse("/agent-test 分析秒杀订单链路");

        assertEquals(FeishuCommandParser.CommandType.AGENT_TEST, command.type());
        assertNull(command.repoId());
        assertEquals("分析秒杀订单链路", command.query());
    }

    @Test
    void agentTestWithoutQuestionIsInvalid() {
        FeishuCommandParser.ParsedCommand command = parser.parse("/agent-test");

        assertEquals(FeishuCommandParser.CommandType.AGENT_TEST_INVALID, command.type());
        assertNull(command.repoId());
        assertNull(command.query());
    }

    @Test
    void parsesNewSessionCommand() {
        FeishuCommandParser.ParsedCommand command = parser.parse("  /new-session  ");

        assertEquals(FeishuCommandParser.CommandType.NEW_SESSION, command.type());
        assertNull(command.repoId());
        assertNull(command.query());
    }

    @Test
    void parsesAgentCommandAndKeepsChineseQuestion() {
        FeishuCommandParser.ParsedCommand command = parser.parse("/agent 分析秒杀订单链路");

        assertEquals(FeishuCommandParser.CommandType.AGENT, command.type());
        assertNull(command.repoId());
        assertEquals("分析秒杀订单链路", command.query());
    }

    @Test
    void agentWithoutQuestionIsInvalid() {
        FeishuCommandParser.ParsedCommand command = parser.parse("/agent");

        assertEquals(FeishuCommandParser.CommandType.AGENT_INVALID, command.type());
        assertNull(command.repoId());
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
