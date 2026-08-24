package com.kama.jchatmind.agent;

public class AgentTaskCancelledException extends RuntimeException {
    public AgentTaskCancelledException(String taskId) {
        super("Agent Task cancelled: " + taskId);
    }
}
