package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class CodeQueryEmbeddingCache {
    private final CodeRagProperties properties;
    private final Map<String, Entry> cache;
    private long hitCount;
    private long missCount;

    public CodeQueryEmbeddingCache(CodeRagProperties properties) {
        this.properties = properties;
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized float[] get(String query) {
        if (!properties.getEmbeddingCache().isEnabled()) {
            missCount++;
            return null;
        }
        String key = key(query);
        Entry entry = cache.get(key);
        if (entry == null || entry.isExpired(ttlMillis())) {
            cache.remove(key);
            missCount++;
            return null;
        }
        hitCount++;
        return Arrays.copyOf(entry.embedding(), entry.embedding().length);
    }

    public synchronized void put(String query, float[] embedding) {
        if (!properties.getEmbeddingCache().isEnabled() || !isCacheable(embedding)) {
            return;
        }
        cache.put(key(query), new Entry(Arrays.copyOf(embedding, embedding.length), System.currentTimeMillis()));
        evictOverflow();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(hitCount, missCount, cache.size());
    }

    public synchronized void clear() {
        cache.clear();
        hitCount = 0;
        missCount = 0;
    }

    private String key(String query) {
        return properties.getEmbeddingModel() + "::" + normalize(query);
    }

    private String normalize(String query) {
        return (query == null ? "" : query.trim())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isCacheable(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return false;
        }
        for (float value : embedding) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return false;
            }
        }
        return true;
    }

    private void evictOverflow() {
        int maxSize = Math.max(1, properties.getEmbeddingCache().getMaxSize());
        while (cache.size() > maxSize) {
            String eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }
    }

    private long ttlMillis() {
        long minutes = Math.max(1, properties.getEmbeddingCache().getTtlMinutes());
        return Duration.ofMinutes(minutes).toMillis();
    }

    private static class Entry {
        private final float[] embedding;
        private final long createdAtMs;

        private Entry(float[] embedding, long createdAtMs) {
            this.embedding = embedding;
            this.createdAtMs = createdAtMs;
        }

        float[] embedding() {
            return embedding;
        }

        boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - createdAtMs > ttlMillis;
        }
    }

    public static class Snapshot {
        private final long hitCount;
        private final long missCount;
        private final int size;

        private Snapshot(long hitCount, long missCount, int size) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.size = size;
        }

        public long hitCount() {
            return hitCount;
        }

        public long missCount() {
            return missCount;
        }

        public int size() {
            return size;
        }
    }
}
