package com.kama.jchatmind.agent;

import com.kama.jchatmind.tool.ToolExecutionContext;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

@Data
@Builder
public class AgentToolCallExecutionRequest {
    private Prompt prompt;
    private ChatResponse chatResponse;
    private ToolCallingManager toolCallingManager;
    private ToolExecutionContext executionContext;
}
