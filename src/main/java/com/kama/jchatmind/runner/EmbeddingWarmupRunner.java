package com.kama.jchatmind.runner;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class EmbeddingWarmupRunner {
    private final CodeRagProperties properties;
    private final RagService ragService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        if (!properties.getEmbeddingWarmup().isEnabled()) {
            return;
        }
        String model = properties.getEmbeddingModel();
        String prompt = properties.getEmbeddingWarmup().getPrompt();
        long started = System.nanoTime();
        log.info("embedding warm-up started, model={}, prompt={}", model, prompt);
        try {
            float[] embedding = ragService.embed(prompt);
            long latencyMs = elapsedMs(started);
            log.info("embedding warm-up success, model={}, prompt={}, dimensions={}, latency_ms={}",
                    model, prompt, embedding == null ? 0 : embedding.length, latencyMs);
        } catch (RuntimeException e) {
            long latencyMs = elapsedMs(started);
            log.warn("embedding warm-up failed, model={}, prompt={}, latency_ms={}, error={}",
                    model, prompt, latencyMs, e.getMessage());
        }
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
