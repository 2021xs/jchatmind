package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.dto.CodeSearchResult;

import java.util.List;
import java.util.Locale;

class CodeRagGroundTruthMatcher {

    boolean matches(CodeSearchResult result, CodeRagEvalCase evalCase) {
        boolean chunkTypeOk = isEmpty(evalCase.expectedChunkTypes)
                || containsAny(List.of(safe(result.getChunkType())), evalCase.expectedChunkTypes);
        if (!chunkTypeOk) {
            return false;
        }

        boolean fileHit = containsAny(List.of(safe(result.getFilePath())), evalCase.expectedFileKeywords);
        boolean symbolHit = containsAny(List.of(
                safe(result.getSymbolName()),
                safe(result.getApiPath()),
                safe(result.getContentPreview()),
                safe(result.getMetadata())
        ), evalCase.expectedSymbolKeywords);
        return fileHit || symbolHit;
    }

    int firstMatchRank(List<CodeSearchResult> evidence, CodeRagEvalCase evalCase) {
        List<CodeSearchResult> safeEvidence = evidence == null ? List.of() : evidence;
        for (int i = 0; i < safeEvidence.size(); i++) {
            if (matches(safeEvidence.get(i), evalCase)) {
                return i + 1;
            }
        }
        return 0;
    }

    boolean hitWithin(List<CodeSearchResult> evidence, CodeRagEvalCase evalCase, int topK) {
        int rank = firstMatchRank(evidence, evalCase);
        return rank > 0 && rank <= topK;
    }

    private boolean containsAny(List<String> haystacks, List<String> needles) {
        if (isEmpty(needles)) {
            return false;
        }
        for (String haystack : haystacks) {
            String normalizedHaystack = safe(haystack).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (!safe(needle).isBlank()
                        && normalizedHaystack.contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
