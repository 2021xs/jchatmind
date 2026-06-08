package com.kama.jchatmind.agent.tools;

import org.springframework.stereotype.Component;

@Component
public class TerminateTool implements Tool {

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "terminate", description = "如果你觉得当前所有的任务已经执行完毕了，就执行这个工具调用")
    public void terminate() {}
}
