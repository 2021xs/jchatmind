package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeSearchService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class CodeSearchServiceImpl implements CodeSearchService {
    private final RagService ragService;
    private final CodeChunkMapper codeChunkMapper;
    private final CodeQueryEmbeddingCache embeddingCache;
    private final CodeRagProperties properties;

    @Override
    public List<CodeSearchResult> search(String repoId, String query, int topK) {
        int maxTopK = Math.max(20, properties.getAnswerEvidence().getRawTopK());
        int limit = Math.max(1, Math.min(topK <= 0 ? 5 : topK, maxTopK));
        float[] embedding = embeddingCache.get(query);
        if (embedding == null) {
            log.debug("code query embedding cache miss");
            embedding = ragService.embed(query);
            embeddingCache.put(query, embedding);
        } else {
            log.debug("code query embedding cache hit");
        }
        List<CodeSearchResult> results = codeChunkMapper.similaritySearch(repoId, toPgVector(embedding), limit);
        results.forEach(this::markRawVector);
        log.info("code search completed: searchMode=RAW_VECTOR, repoId={}, topK={}, resultCount={}",
                repoId, limit, results.size());
        return results;
    }

    private void markRawVector(CodeSearchResult result) {
        double score = result.getScore() == null ? 0 : result.getScore();
        result.setOriginalScore(score);
        result.setBoostScore(0.0);
        result.setRerankerScore(null);
        result.setFinalScore(score);
        result.setRerankSource("RAW_VECTOR");
        result.setRerankReasons("");
    }

    private String toPgVector(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            sb.append(values[i]);
            if (i < values.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
