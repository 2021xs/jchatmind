package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();

    @Test
    void configuresBoundedNamedBusinessExecutors() {
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) config.taskExecutor();
        ThreadPoolTaskExecutor selectorExecutor = config.codeEvidenceSelectorExecutor();
        try {
            assertEquals("async-event-", taskExecutor.getThreadNamePrefix());
            assertEquals(4, taskExecutor.getCorePoolSize());
            assertEquals(10, taskExecutor.getMaxPoolSize());
            assertEquals(100, taskExecutor.getQueueCapacity());

            assertEquals("code-evidence-selector-", selectorExecutor.getThreadNamePrefix());
            assertEquals(4, selectorExecutor.getCorePoolSize());
            assertEquals(4, selectorExecutor.getMaxPoolSize());
            assertEquals(100, selectorExecutor.getQueueCapacity());
            assertTrue(selectorExecutor.getThreadPoolExecutor().getRejectedExecutionHandler()
                    instanceof java.util.concurrent.ThreadPoolExecutor.AbortPolicy);
        } finally {
            taskExecutor.shutdown();
            selectorExecutor.shutdown();
        }
    }
}
