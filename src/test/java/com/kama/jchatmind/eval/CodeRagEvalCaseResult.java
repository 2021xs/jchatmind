package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.dto.CodeSearchResult;

import java.util.List;

record CodeRagEvalCaseResult(
        CodeRagEvalCase evalCase,
        List<CodeSearchResult> rawCandidates,
        List<CodeSearchResult> selectedEvidence,
        int groundTruthRawRank,
        int groundTruthSelectedRank,
        CodeRagFailureType failureType,
        boolean fallback,
        boolean jsonParseOk,
        boolean cacheHit,
        List<String> selectorProposedChunkIds,
        List<String> selectorValidChunkIds,
        List<String> selectorInvalidChunkIds,
        List<String> selectorProposedCandidateIds,
        List<String> selectorValidCandidateIds,
        List<String> selectorInvalidCandidateIds,
        String fallbackReason,
        boolean emptySelectorResult,
        String error,
        long embeddingLatencyMs,
        long retrievalLatencyMs,
        long selectorLatencyMs,
        long totalLatencyMs,
        Integer selectorPromptTokens,
        Integer selectorCompletionTokens,
        Integer selectorTotalTokens,
        boolean selectorUsageAvailable,
        int selectorPromptChars,
        int selectorCandidateSectionChars) {
}
