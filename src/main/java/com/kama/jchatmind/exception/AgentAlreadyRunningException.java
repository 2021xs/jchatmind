package com.kama.jchatmind.exception;

import lombok.Getter;

@Getter
public class AgentAlreadyRunningException extends BizException {

    public static final String USER_MESSAGE = "当前会话正在处理中，请稍后再试。";

    private final String runningTaskId;

    public AgentAlreadyRunningException(String runningTaskId) {
        super(USER_MESSAGE);
        this.runningTaskId = runningTaskId;
    }
}
