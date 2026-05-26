package com.kama.jchatmind.tool;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ToolExecutionContext {
    private String taskId;
    private String stepId;
    private String traceId;
    private String sessionId;
    private String agentId;
    private String modelName;
    private List<String> runtimeToolNames;
}
