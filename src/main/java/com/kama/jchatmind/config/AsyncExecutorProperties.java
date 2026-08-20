package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jchatmind.async")
public class AsyncExecutorProperties {

    private Pool task = Pool.of(4, 8, 16, 60, 60);
    private Pool codeEvidenceSelector = Pool.of(4, 4, 6, 60, 60);
    private Pool tool = Pool.of(4, 8, 16, 60, 30);

    @Data
    public static class Pool {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private int keepAliveSeconds;
        private int awaitTerminationSeconds;

        private static Pool of(int corePoolSize,
                               int maxPoolSize,
                               int queueCapacity,
                               int keepAliveSeconds,
                               int awaitTerminationSeconds) {
            Pool pool = new Pool();
            pool.setCorePoolSize(corePoolSize);
            pool.setMaxPoolSize(maxPoolSize);
            pool.setQueueCapacity(queueCapacity);
            pool.setKeepAliveSeconds(keepAliveSeconds);
            pool.setAwaitTerminationSeconds(awaitTerminationSeconds);
            return pool;
        }
    }
}
