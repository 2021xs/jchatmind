package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.RagSearchResult;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeToolsTest {

    @Test
    void formatsSelectedEvidenceInRetrievalOrderWithoutChangingTheQuery() {
        RagService ragService = mock(RagService.class);
        when(ragService.similaritySearchWithMetadata("kb-1", "original query"))
                .thenReturn(List.of(result("chunk-1", "first"), result("chunk-2", "second")));

        String canonical = new KnowledgeTools(ragService).knowledgeQuery("kb-1", "original query");

        assertThat(canonical)
                .contains("chunkId: chunk-1", "chunkId: chunk-2", "[content]\nfirst", "[content]\nsecond")
                .containsSubsequence("chunkId: chunk-1", "chunkId: chunk-2");
        verify(ragService).similaritySearchWithMetadata("kb-1", "original query");
    }

    @Test
    void returnsCompleteFormattedEvidenceBeyondLegacySixThousandCharacterLimit() {
        String tailMarker = "_KNOWLEDGE_CANONICAL_TAIL";
        RagService ragService = mock(RagService.class);
        when(ragService.similaritySearchWithMetadata("kb-1", "large query"))
                .thenReturn(List.of(result("chunk-large", "x".repeat(7_000) + tailMarker)));

        String canonical = new KnowledgeTools(ragService).knowledgeQuery("kb-1", "large query");

        assertThat(canonical)
                .hasSizeGreaterThan(6_000)
                .contains("chunkId: chunk-large")
                .endsWith(tailMarker)
                .doesNotContain("...[truncated]");
    }

    @Test
    void emptyRetrievalKeepsExistingNoEvidenceContract() {
        RagService ragService = mock(RagService.class);
        when(ragService.similaritySearchWithMetadata("kb-1", "missing")).thenReturn(List.of());

        assertThat(new KnowledgeTools(ragService).knowledgeQuery("kb-1", "missing"))
                .isEqualTo("未检索到相关知识片段。");
    }

    private RagSearchResult result(String chunkId, String content) {
        return RagSearchResult.builder()
                .chunkId(chunkId)
                .title("title-" + chunkId)
                .sourceType("document_chunk")
                .sourceId("document-1")
                .score(0.75)
                .metadata("{\"category\":\"test\"}")
                .content(content)
                .build();
    }
}
