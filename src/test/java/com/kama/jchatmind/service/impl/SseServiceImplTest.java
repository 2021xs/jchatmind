package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseServiceImplTest {

    @Test
    void completeShouldRemoveEmitter() throws Exception {
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper());

        service.connect("session-1");
        assertTrue(clients(service).containsKey("session-1"));

        service.complete("session-1");

        assertFalse(clients(service).containsKey("session-1"));
    }

    @Test
    void completeWithErrorShouldRemoveEmitter() throws Exception {
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper());

        service.connect("session-2");
        assertTrue(clients(service).containsKey("session-2"));

        service.completeWithError("session-2", new RuntimeException("boom"));

        assertFalse(clients(service).containsKey("session-2"));
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, SseEmitter> clients(SseServiceImpl service) throws Exception {
        Field field = SseServiceImpl.class.getDeclaredField("clients");
        field.setAccessible(true);
        return (ConcurrentMap<String, SseEmitter>) field.get(service);
    }
}
