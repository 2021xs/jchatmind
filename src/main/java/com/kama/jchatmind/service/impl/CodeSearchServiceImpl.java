package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.dto.CodeSearchExecutionResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeSearchService;
import com.kama.jchatmind.service.EmbeddingService;
import com.kama.jchatmind.util.PgVectorUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class CodeSearchServiceImpl implements CodeSearchService {
    private static final String STATUS_READY = "READY";

    private final EmbeddingService embeddingService;
    private final CodeChunkMapper codeChunkMapper;
    private final CodeRepositoryMapper codeRepositoryMapper;
    private final CodeQueryEmbeddingCache embeddingCache;
    private final CodeRagProperties properties;

    @Override
    public List<CodeSearchResult> search(String repoId, String query, int topK) {
        return searchWithTrace(repoId, query, topK).getCandidates();
    }

    @Override
    public CodeSearchExecutionResult searchWithTrace(String repoId, String query, int topK) {
        ensureReadyRepository(repoId);
        int maxTopK = Math.max(20, properties.getAnswerEvidence().getRawTopK());
        int limit = Math.max(1, Math.min(topK <= 0 ? 5 : topK, maxTopK));
        float[] embedding = embeddingCache.get(query);
        boolean cacheHit = embedding != null;
        long embeddingLatencyMs = 0;
        if (embedding == null) {
            log.debug("code query embedding cache miss");
            long embeddingStarted = System.nanoTime();
            embedding = embeddingService.embed(query);
            embeddingLatencyMs = elapsedMs(embeddingStarted);
            embeddingCache.put(query, embedding);
        } else {
            log.debug("code query embedding cache hit");
        }
        long retrievalStarted = System.nanoTime();
        List<CodeSearchResult> results = codeChunkMapper.similaritySearch(repoId, PgVectorUtils.toLiteral(embedding), limit);
        long retrievalLatencyMs = elapsedMs(retrievalStarted);
        results.forEach(this::markRawVector);
        log.info("code search completed: searchMode=RAW_VECTOR, repoId={}, topK={}, resultCount={}, embeddingLatencyMs={}, retrievalLatencyMs={}, cacheHit={}",
                repoId, limit, results.size(), embeddingLatencyMs, retrievalLatencyMs, cacheHit);
        return CodeSearchExecutionResult.builder()
                .candidates(results)
                .embeddingLatencyMs(embeddingLatencyMs)
                .retrievalLatencyMs(retrievalLatencyMs)
                .cacheHit(cacheHit)
                .build();
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private void ensureReadyRepository(String repoId) {
        if (!StringUtils.hasLength(repoId)) {
            throw new BizException("repoId 不能为空");
        }
        CodeRepository repository = codeRepositoryMapper.selectById(repoId);
        if (repository == null) {
            throw new BizException("代码仓库不存在: " + repoId);
        }
        if (!STATUS_READY.equals(repository.getStatus())) {
            throw new BizException("当前代码仓库尚未导入完成或导入失败，无法检索: status=" + repository.getStatus());
        }
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

}
