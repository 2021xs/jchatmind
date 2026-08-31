package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.config.ToolTimeoutProperties;
import com.kama.jchatmind.config.ToolResultProperties;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.service.impl.EstimatedTokenCounter;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.tool.ToolRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public final class ToolCallBatchExecutorFixture implements AutoCloseable {
    private final ThreadPoolTaskExecutor executor;
    private final ToolCallBatchExecutor batchExecutor;

    public ToolCallBatchExecutorFixture(ToolExecutionService toolExecutionService, ToolRegistry toolRegistry) {
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(2);
        this.executor.setMaxPoolSize(4);
        this.executor.setQueueCapacity(8);
        this.executor.setThreadNamePrefix("test-agent-tool-");
        this.executor.initialize();
        ContextCompressionProperties compressionProperties = new ContextCompressionProperties();
        this.batchExecutor = new ToolCallBatchExecutor(
                toolExecutionService, executor, new ToolTimeoutProperties(),
                new ToolResultGuard(new ToolResultProperties()),
                new ToolDuplicateCallDetector(new ObjectMapper(), new ToolDuplicateDetectionProperties()),
                toolRegistry, new EstimatedTokenCounter(compressionProperties), compressionProperties);
    }

    public ToolCallBatchExecutor batchExecutor() {
        return batchExecutor;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
