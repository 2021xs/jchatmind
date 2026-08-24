package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    @Test
    void configuresBoundedNamedBusinessExecutors() {
        AsyncConfig config = new AsyncConfig(new AsyncExecutorProperties());
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) config.taskExecutor();
        ThreadPoolTaskExecutor selectorExecutor = config.codeEvidenceSelectorExecutor();
        ThreadPoolTaskExecutor toolExecutor = config.toolExecutor();
        try {
            assertEquals("async-event-", taskExecutor.getThreadNamePrefix());
            assertEquals(4, taskExecutor.getCorePoolSize());
            assertEquals(8, taskExecutor.getMaxPoolSize());
            assertEquals(16, taskExecutor.getQueueCapacity());
            assertEquals(60, taskExecutor.getKeepAliveSeconds());
            assertTrue(taskExecutor.getThreadPoolExecutor().getRejectedExecutionHandler()
                    instanceof java.util.concurrent.ThreadPoolExecutor.AbortPolicy);

            assertEquals("code-evidence-selector-", selectorExecutor.getThreadNamePrefix());
            assertEquals(4, selectorExecutor.getCorePoolSize());
            assertEquals(4, selectorExecutor.getMaxPoolSize());
            assertEquals(6, selectorExecutor.getQueueCapacity());
            assertEquals(60, selectorExecutor.getKeepAliveSeconds());
            assertTrue(selectorExecutor.getThreadPoolExecutor().getRejectedExecutionHandler()
                    instanceof java.util.concurrent.ThreadPoolExecutor.AbortPolicy);

            assertEquals("agent-tool-", toolExecutor.getThreadNamePrefix());
            assertEquals(4, toolExecutor.getCorePoolSize());
            assertEquals(8, toolExecutor.getMaxPoolSize());
            assertEquals(16, toolExecutor.getQueueCapacity());
            assertEquals(60, toolExecutor.getKeepAliveSeconds());
            assertTrue(toolExecutor.getThreadPoolExecutor().getRejectedExecutionHandler()
                    instanceof java.util.concurrent.ThreadPoolExecutor.AbortPolicy);
        } finally {
            taskExecutor.shutdown();
            selectorExecutor.shutdown();
            toolExecutor.shutdown();
        }
    }

    @Test
    void appliesExternalizedPoolProperties() {
        AsyncExecutorProperties properties = new AsyncExecutorProperties();
        AsyncExecutorProperties.Pool task = properties.getTask();
        task.setCorePoolSize(2);
        task.setMaxPoolSize(3);
        task.setQueueCapacity(5);
        task.setKeepAliveSeconds(45);
        task.setAwaitTerminationSeconds(10);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig(properties).taskExecutor();
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(3, executor.getMaxPoolSize());
            assertEquals(5, executor.getQueueCapacity());
            assertEquals(45, executor.getKeepAliveSeconds());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void bindsPoolPropertiesFromSpringEnvironment() {
        new ApplicationContextRunner()
                .withUserConfiguration(AsyncConfig.class)
                .withPropertyValues(
                        "jchatmind.async.task.core-pool-size=2",
                        "jchatmind.async.task.max-pool-size=3",
                        "jchatmind.async.task.queue-capacity=5",
                        "jchatmind.async.task.keep-alive-seconds=45",
                        "jchatmind.async.task.await-termination-seconds=10")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) context.getBean("taskExecutor");
                    assertEquals(2, executor.getCorePoolSize());
                    assertEquals(3, executor.getMaxPoolSize());
                    assertEquals(5, executor.getQueueCapacity());
                    assertEquals(45, executor.getKeepAliveSeconds());
                });
    }

    @Test
    void rejectsInvalidPoolBounds() {
        AsyncExecutorProperties properties = new AsyncExecutorProperties();
        properties.getTask().setCorePoolSize(5);
        properties.getTask().setMaxPoolSize(4);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AsyncConfig(properties).taskExecutor());

        assertTrue(error.getMessage().contains("max-pool-size must be >= core-pool-size"));
    }

    @Test
    void defaultPoolsReachDocumentedCapacityAndRejectOverflow() throws InterruptedException {
        AsyncConfig config = new AsyncConfig(new AsyncExecutorProperties());
        assertCapacityBoundary((ThreadPoolTaskExecutor) config.taskExecutor(), 8, 16);
        assertCapacityBoundary(config.codeEvidenceSelectorExecutor(), 4, 6);
        assertCapacityBoundary(config.toolExecutor(), 8, 16);
    }

    @Test
    void toolExecutorRequestsInterruptOnShutdown() throws InterruptedException {
        ThreadPoolTaskExecutor executor = new AsyncConfig(new AsyncExecutorProperties()).toolExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    private void assertCapacityBoundary(ThreadPoolTaskExecutor executor,
                                        int runningCapacity,
                                        int queueCapacity) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(runningCapacity);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blockingTask = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            for (int i = 0; i < runningCapacity + queueCapacity; i++) {
                executor.execute(blockingTask);
            }

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(runningCapacity, executor.getActiveCount());
            assertEquals(queueCapacity, executor.getThreadPoolExecutor().getQueue().size());
            assertThrows(TaskRejectedException.class, () -> executor.execute(blockingTask));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
