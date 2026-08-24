package com.kama.jchatmind.exception;

import lombok.Getter;

@Getter
public class AgentAlreadyRunningException extends BizException {
    public static final String ERROR_TYPE = "SESSION_TASK_ALREADY_RUNNING";
    public static final String USER_MESSAGE = ERROR_TYPE + ": 当前会话已有任务运行中。";

    private final String runningTaskId;

    public AgentAlreadyRunningException(String runningTaskId) {
        super(USER_MESSAGE);
        this.runningTaskId = runningTaskId;
    }
}
