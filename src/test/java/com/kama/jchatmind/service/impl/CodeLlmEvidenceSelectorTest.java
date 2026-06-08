package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeLlmEvidenceSelectorTest {

    @Test
    void timeoutCancelsSharedExecutorTaskAndFallsBackToCandidateOrder() throws Exception {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<String> future = mock(Future.class);
        CodeRagProperties properties = new CodeRagProperties();
        properties.getLlmSelector().setTimeoutMs(10);

        when(registry.get(properties.getLlmSelector().getModel())).thenReturn(chatClient);
        when(executor.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new TimeoutException("timed out"));

        CodeLlmEvidenceSelector selector =
                new CodeLlmEvidenceSelector(registry, properties, new ObjectMapper(), executor);
        var result = selector.select("query", List.of(candidate("raw-1"), candidate("raw-2")));

        assertTrue(result.isFallback());
        assertFalse(result.isJsonParseOk());
        assertEquals(List.of("raw-1", "raw-2"), result.getSelectedChunkIds());
        verify(future).cancel(true);
    }

    private CodeEvidenceCandidateCard candidate(String chunkId) {
        return CodeEvidenceCandidateCard.builder()
                .chunkId(chunkId)
                .chunkType("SERVICE_METHOD")
                .filePath("Service.java")
                .snippet("snippet")
                .build();
    }
}
