package com.kama.jchatmind.tool;

import com.kama.jchatmind.agent.tools.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ToolRegistryInitializer implements SmartInitializingSingleton {

    private final InMemoryToolRegistry toolRegistry;
    private final List<Tool> tools;

    @Override
    public void afterSingletonsInstantiated() {
        toolRegistry.initialize(tools);
    }
}
