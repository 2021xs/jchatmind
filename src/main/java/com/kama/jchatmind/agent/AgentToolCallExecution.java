package com.kama.jchatmind.agent;

import com.kama.jchatmind.tool.ToolExecutionRecord;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

@Data
@Builder
public class AgentToolCallExecution {
    private Status status;
    private List<ToolExecutionRecord> records;
    private ToolExecutionResult toolExecutionResult;
    private ToolResponseMessage toolResponseMessage;
    private RuntimeException error;

    public enum Status {
        SUCCESS,
        FAILED
    }

    public boolean succeeded() {
        return status == Status.SUCCESS;
    }
}
