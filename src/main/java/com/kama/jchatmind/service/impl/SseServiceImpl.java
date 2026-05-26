package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@AllArgsConstructor
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        SseEmitter previous = clients.put(chatSessionId, emitter);
        if (previous != null) {
            previous.complete();
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException e) {
            clients.remove(chatSessionId, emitter);
            emitter.completeWithError(e);
            throw new RuntimeException(e);
        }

        emitter.onCompletion(() -> clients.remove(chatSessionId, emitter));
        emitter.onTimeout(() -> clients.remove(chatSessionId, emitter));
        emitter.onError((error) -> clients.remove(chatSessionId, emitter));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter != null) {
            try {
                // 将消息转换为字符串
                String sseMessageStr = objectMapper.writeValueAsString(message);
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(sseMessageStr)
                );
            } catch (IOException e) {
                completeWithError(chatSessionId, e);
                throw new RuntimeException(e);
            }
        } else {
            throw new RuntimeException("No client found for chatSessionId: " + chatSessionId);
        }
    }

    @Override
    public void sendEvent(String chatSessionId, AgentSseEvent event) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter != null) {
            try {
                String eventStr = objectMapper.writeValueAsString(event);
                emitter.send(SseEmitter.event()
                        .id(event.getEventId())
                        .name(event.getType().getValue())
                        .data(eventStr)
                );
            } catch (IOException e) {
                completeWithError(chatSessionId, e);
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void complete(String chatSessionId) {
        SseEmitter emitter = clients.remove(chatSessionId);
        if (emitter != null) {
            emitter.complete();
        }
    }

    @Override
    public void completeWithError(String chatSessionId, Throwable error) {
        SseEmitter emitter = clients.remove(chatSessionId);
        if (emitter != null) {
            emitter.completeWithError(error);
        }
    }
}
