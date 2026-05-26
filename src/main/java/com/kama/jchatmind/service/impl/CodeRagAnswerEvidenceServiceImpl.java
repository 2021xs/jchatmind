package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.CodeSearchService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class CodeRagAnswerEvidenceServiceImpl implements CodeRagAnswerEvidenceService {
    private final CodeSearchService codeSearchService;
    private final CodeLlmEvidenceSelector evidenceSelector;
    private final CodeRagProperties properties;

    @Override
    public CodeAnswerEvidenceResult retrieve(String repoId, String query) {
        int rawTopK = positive(properties.getAnswerEvidence().getRawTopK(), 50);
        int finalTopK = positive(properties.getAnswerEvidence().getFinalTopK(), 5);

        List<CodeSearchResult> rawCandidates = codeSearchService.search(repoId, query, rawTopK);
        markRawVectorSource(rawCandidates);
        List<CodeEvidenceCandidateCard> cards = toCandidateCards(rawCandidates);

        CodeEvidenceSelectionResult selection = selectEvidence(query, cards);
        List<CodeSearchResult> selected = selectedResults(rawCandidates, selection.getSelectedChunkIds(), finalTopK);
        boolean fallback = selection.isFallback();
        if (fallback || selected.isEmpty()) {
            selected = fallbackCandidates(rawCandidates, finalTopK);
            fallback = true;
        }

        logSummary(repoId, query, rawCandidates, selected, selection, fallback);
        return CodeAnswerEvidenceResult.builder()
                .selectedEvidence(selected)
                .selectorReason(selection.getReason())
                .answerType(selection.getAnswerType())
                .fallback(fallback)
                .jsonParseOk(selection.isJsonParseOk())
                .selectorLatencyMs(selection.getLatencyMs())
                .candidateCount(rawCandidates.size())
                .rawCount(rawCandidates.size())
                .build();
    }

    private void markRawVectorSource(List<CodeSearchResult> candidates) {
        for (CodeSearchResult candidate : candidates) {
            candidate.setRerankSource(emptyToDefault(candidate.getRerankSource(), "RAW_VECTOR"));
        }
    }

    private CodeEvidenceSelectionResult selectEvidence(String query, List<CodeEvidenceCandidateCard> cards) {
        try {
            return evidenceSelector.select(query, cards);
        } catch (RuntimeException e) {
            log.warn("code rag evidence selector failed, fallback to candidate order: query={}, error={}",
                    summarize(query), e.getMessage());
            CodeEvidenceSelectionResult fallback = fallbackSelection(cards, "selector exception: " + e.getMessage());
            fallback.setJsonParseOk(false);
            return fallback;
        }
    }

    private CodeEvidenceSelectionResult fallbackSelection(List<CodeEvidenceCandidateCard> cards, String reason) {
        int limit = Math.min(positive(properties.getAnswerEvidence().getFinalTopK(), 5), cards.size());
        return CodeEvidenceSelectionResult.builder()
                .selectedChunkIds(cards.stream().limit(limit).map(CodeEvidenceCandidateCard::getChunkId).toList())
                .reason(reason)
                .answerType("UNKNOWN")
                .jsonParseOk(true)
                .fallback(true)
                .latencyMs(0)
                .build();
    }

    private List<CodeEvidenceCandidateCard> toCandidateCards(List<CodeSearchResult> candidates) {
        List<CodeEvidenceCandidateCard> cards = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            CodeSearchResult result = candidates.get(i);
            cards.add(CodeEvidenceCandidateCard.builder()
                    .chunkId(candidateId(result, i))
                    .chunkType(result.getChunkType())
                    .filePath(result.getFilePath())
                    .symbolName(result.getSymbolName())
                    .apiPath(result.getApiPath())
                    .httpMethod(result.getHttpMethod())
                    .metadataSummary(truncate(result.getMetadata(), 220))
                    .snippet(truncate(result.getContentPreview(), properties.getLlmSelector().getMaxCandidateChars()))
                    .evidenceRole(evidenceRole(result))
                    .evidenceHint(evidenceHint(result))
                    .source(result.getRerankSource())
                    .rawRank(i + 1)
                    .candidateRank(i + 1)
                    .candidateScore(result.getFinalScore() == null ? result.getScore() : result.getFinalScore())
                    .build());
        }
        return cards;
    }

    private List<CodeSearchResult> selectedResults(List<CodeSearchResult> candidates, List<String> selectedChunkIds, int finalTopK) {
        if (selectedChunkIds == null || selectedChunkIds.isEmpty()) {
            return List.of();
        }
        List<CodeSearchResult> selected = new ArrayList<>();
        for (String selectedChunkId : selectedChunkIds) {
            for (int i = 0; i < candidates.size(); i++) {
                CodeSearchResult candidate = candidates.get(i);
                if (candidateId(candidate, i).equals(selectedChunkId)) {
                    selected.add(candidate);
                    break;
                }
            }
            if (selected.size() >= finalTopK) {
                break;
            }
        }
        return selected;
    }

    private List<CodeSearchResult> fallbackCandidates(List<CodeSearchResult> candidates, int finalTopK) {
        return candidates.stream().limit(finalTopK).toList();
    }

    private String evidenceRole(CodeSearchResult result) {
        String chunkType = safe(result.getChunkType());
        if ("CONTROLLER_API".equalsIgnoreCase(chunkType)) {
            return "API_ENTRY";
        }
        if ("SERVICE_METHOD".equalsIgnoreCase(chunkType)) {
            return "SERVICE_LOGIC";
        }
        if ("MYBATIS_SQL".equalsIgnoreCase(chunkType)) {
            return "SQL_STATEMENT";
        }
        return "";
    }

    private String evidenceHint(CodeSearchResult result) {
        String chunkType = safe(result.getChunkType());
        if ("CONTROLLER_API".equalsIgnoreCase(chunkType)) {
            return "This chunk is an API entry point.";
        }
        if ("SERVICE_METHOD".equalsIgnoreCase(chunkType)) {
            return "This chunk contains service-layer business logic.";
        }
        if ("MYBATIS_SQL".equalsIgnoreCase(chunkType)) {
            return "This chunk is a MyBatis SQL statement.";
        }
        return "";
    }

    private void logSummary(String repoId,
                            String query,
                            List<CodeSearchResult> rawCandidates,
                            List<CodeSearchResult> selected,
                            CodeEvidenceSelectionResult selection,
                            boolean fallback) {
        log.info("code rag answer evidence completed: repoId={}, query={}, rawCount={}, selectedCount={}, selectorLatencyMs={}, fallback={}, jsonParseOk={}",
                repoId, summarize(query), rawCandidates.size(),
                selected.size(), selection.getLatencyMs(), fallback, selection.isJsonParseOk());
        for (CodeSearchResult result : selected) {
            log.info("code rag selected evidence: chunkId={}, filePath={}, chunkType={}, source={}",
                    result.getChunkId(), result.getFilePath(), result.getChunkType(), result.getRerankSource());
        }
    }

    private String candidateId(CodeSearchResult result, int index) {
        return result.getChunkId() == null || result.getChunkId().isBlank()
                ? "candidate-" + index
                : result.getChunkId();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "\n...[truncated]";
    }

    private String summarize(String query) {
        return truncate(safe(query).replaceAll("\\s+", " ").trim(), 120);
    }

    private String emptyToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private int positive(int value, int defaultValue) {
        return value <= 0 ? defaultValue : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
