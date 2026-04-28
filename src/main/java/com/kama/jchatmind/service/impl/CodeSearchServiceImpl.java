package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeSearchService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class CodeSearchServiceImpl implements CodeSearchService {
    private final RagService ragService;
    private final CodeChunkMapper codeChunkMapper;

    @Override
    public List<CodeSearchResult> search(String repoId, String query, int topK) {
        int limit = Math.max(1, Math.min(topK <= 0 ? 5 : topK, 20));
        String queryEmbedding = toPgVector(ragService.embed(query));
        return codeChunkMapper.similaritySearch(repoId, queryEmbedding, limit);
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
