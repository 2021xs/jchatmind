package com.kama.jchatmind.runner;

import com.kama.jchatmind.config.AgentObservabilityProperties;
import com.kama.jchatmind.service.AgentTaskLogService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class AgentLogRecoveryRunner implements ApplicationRunner {
    private final AgentObservabilityProperties properties;
    private final AgentTaskLogService agentTaskLogService;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isRecoveryEnabled()) {
            log.info("agent observability recovery skipped: disabled");
            return;
        }
        int recovered = agentTaskLogService.recoverStaleRunningTasks(
                properties.getStaleRunningThresholdMinutes()
        );
        if (recovered > 0) {
            log.warn("agent observability recovery marked stale RUNNING tasks: count={}, thresholdMinutes={}",
                    recovered, properties.getStaleRunningThresholdMinutes());
        } else {
            log.info("agent observability recovery completed: no stale RUNNING tasks");
        }
    }
}
