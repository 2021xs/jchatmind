package com.kama.jchatmind.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableConfigurationProperties(AsyncExecutorProperties.class)
public class AsyncConfig {

    private final AsyncExecutorProperties properties;

    public AsyncConfig(AsyncExecutorProperties properties) {
        this.properties = properties;
    }

    @Bean
    @Primary
    public Executor taskExecutor() {
        return createExecutor(properties.getTask(), "async-event-", true);
    }

    @Bean(name = "codeEvidenceSelectorExecutor")
    public ThreadPoolTaskExecutor codeEvidenceSelectorExecutor() {
        return createExecutor(properties.getCodeEvidenceSelector(), "code-evidence-selector-", true);
    }

    @Bean(name = "toolExecutor")
    public ThreadPoolTaskExecutor toolExecutor() {
        return createExecutor(properties.getTool(), "agent-tool-", false);
    }

    private ThreadPoolTaskExecutor createExecutor(AsyncExecutorProperties.Pool pool,
                                                  String threadNamePrefix,
                                                  boolean waitForTasksToCompleteOnShutdown) {
        validate(pool, threadNamePrefix);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCorePoolSize());
        executor.setMaxPoolSize(pool.getMaxPoolSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setKeepAliveSeconds(pool.getKeepAliveSeconds());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(waitForTasksToCompleteOnShutdown);
        executor.setAwaitTerminationSeconds(pool.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }

    private void validate(AsyncExecutorProperties.Pool pool, String threadNamePrefix) {
        if (pool == null) {
            throw new IllegalArgumentException(threadNamePrefix + " executor properties must not be null");
        }
        if (pool.getCorePoolSize() <= 0) {
            throw new IllegalArgumentException(threadNamePrefix + " core-pool-size must be greater than 0");
        }
        if (pool.getMaxPoolSize() < pool.getCorePoolSize()) {
            throw new IllegalArgumentException(threadNamePrefix + " max-pool-size must be >= core-pool-size");
        }
        if (pool.getQueueCapacity() < 0) {
            throw new IllegalArgumentException(threadNamePrefix + " queue-capacity must be >= 0");
        }
        if (pool.getKeepAliveSeconds() < 0) {
            throw new IllegalArgumentException(threadNamePrefix + " keep-alive-seconds must be >= 0");
        }
        if (pool.getAwaitTerminationSeconds() < 0) {
            throw new IllegalArgumentException(threadNamePrefix + " await-termination-seconds must be >= 0");
        }
    }
}
