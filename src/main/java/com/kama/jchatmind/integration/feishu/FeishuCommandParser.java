package com.kama.jchatmind.integration.feishu;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class FeishuCommandParser {

    static final String HELP_COMMAND = "/help";
    static final String ASK_CODE_COMMAND = "/ask-code";
    static final String AGENT_TEST_COMMAND = "/agent-test";
    static final String NEW_SESSION_COMMAND = "/new-session";
    static final String AGENT_COMMAND = "/agent";

    public boolean isHelpCommand(String text) {
        return parse(text).type() == CommandType.HELP;
    }

    public ParsedCommand parse(String text) {
        if (!StringUtils.hasText(text)) {
            return ParsedCommand.unknown();
        }
        String trimmed = text.trim();
        String[] commandParts = trimmed.split("\\s+", 2);
        String commandName = commandParts[0];
        if (HELP_COMMAND.equals(commandName)) {
            return new ParsedCommand(CommandType.HELP, null, null);
        }
        if (NEW_SESSION_COMMAND.equals(commandName)) {
            return new ParsedCommand(CommandType.NEW_SESSION, null, null);
        }
        if (AGENT_TEST_COMMAND.equals(commandName)) {
            String question = commandParts.length > 1 ? commandParts[1].trim() : "";
            if (!StringUtils.hasText(question)) {
                return new ParsedCommand(CommandType.AGENT_TEST_INVALID, null, null);
            }
            return new ParsedCommand(CommandType.AGENT_TEST, null, question);
        }
        if (AGENT_COMMAND.equals(commandName)) {
            String question = commandParts.length > 1 ? commandParts[1].trim() : "";
            if (!StringUtils.hasText(question)) {
                return new ParsedCommand(CommandType.AGENT_INVALID, null, null);
            }
            return new ParsedCommand(CommandType.AGENT, null, question);
        }
        if (!ASK_CODE_COMMAND.equals(commandName)) {
            return ParsedCommand.unknown();
        }

        String args = commandParts.length > 1 ? commandParts[1].trim() : "";
        if (!StringUtils.hasText(args)) {
            return new ParsedCommand(CommandType.ASK_CODE_INVALID, null, null);
        }
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || !StringUtils.hasText(parts[1])) {
            return new ParsedCommand(CommandType.ASK_CODE_INVALID, parts[0], null);
        }
        return new ParsedCommand(CommandType.ASK_CODE, parts[0], parts[1].trim());
    }

    public enum CommandType {
        HELP,
        ASK_CODE,
        ASK_CODE_INVALID,
        AGENT_TEST,
        AGENT_TEST_INVALID,
        NEW_SESSION,
        AGENT,
        AGENT_INVALID,
        UNKNOWN
    }

    public record ParsedCommand(CommandType type, String repoId, String query) {
        static ParsedCommand unknown() {
            return new ParsedCommand(CommandType.UNKNOWN, null, null);
        }
    }
}
