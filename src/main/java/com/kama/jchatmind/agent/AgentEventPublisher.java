package com.kama.jchatmind.agent;

import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class AgentEventPublisher {
    private final SseService sseService;

    public void sendMessage(String sessionId, SseMessage message) {
        if (sessionId == null || message == null) {
            return;
        }
        try {
            sseService.send(sessionId, message);
        } catch (Exception e) {
            log.warn("Failed to send chat SSE message: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    public void publish(String taskId, String sessionId, AgentSseEvent.Type type, Map<String, Object> payload) {
        if (taskId == null || sessionId == null || type == null) {
            return;
        }
        try {
            sseService.sendEvent(sessionId, AgentSseEvent.of(taskId, sessionId, type, payload));
        } catch (Exception e) {
            log.warn("Failed to send Agent SSE event: type={}, taskId={}, error={}",
                    type, taskId, e.getMessage());
        }
    }

    public void complete(String sessionId, String taskId) {
        if (sessionId == null) {
            return;
        }
        try {
            sseService.complete(sessionId);
        } catch (Exception e) {
            log.warn("Failed to complete SSE after Agent error: taskId={}, error={}",
                    taskId, e.getMessage());
        }
    }
}
