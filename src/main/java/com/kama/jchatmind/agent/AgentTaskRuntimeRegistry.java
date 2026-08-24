package com.kama.jchatmind.agent;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AgentTaskRuntimeRegistry {
    private final ConcurrentMap<String, AgentTaskControl> controls = new ConcurrentHashMap<>();

    public AgentTaskControl register(String taskId, String sessionId) {
        return controls.computeIfAbsent(taskId, ignored -> new AgentTaskControl(taskId, sessionId));
    }

    public Optional<AgentTaskControl> find(String taskId) {
        return Optional.ofNullable(controls.get(taskId));
    }

    public void remove(String taskId, AgentTaskControl control) {
        controls.remove(taskId, control);
    }
}
