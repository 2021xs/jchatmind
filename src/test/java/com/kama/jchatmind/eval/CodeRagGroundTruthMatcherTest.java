package com.kama.jchatmind.eval;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRagGroundTruthMatcherTest {
    private final CodeRagGroundTruthMatcher matcher = new CodeRagGroundTruthMatcher();

    @Test
    void matchesCurrentKeywordLevelFileOrSymbolSemantics() {
        CodeRagEvalCase evalCase = evalCase();
        assertTrue(matcher.matches(result("src/LoginInterceptor.java", "wrong", "JAVA_METHOD"), evalCase));
        assertTrue(matcher.matches(result("src/Other.java", "LoginInterceptor#preHandle", "JAVA_METHOD"), evalCase));
    }

    @Test
    void rejectsWrongFileAndSymbolOrWrongChunkType() {
        CodeRagEvalCase evalCase = evalCase();
        assertFalse(matcher.matches(result("src/Other.java", "Other#run", "JAVA_METHOD"), evalCase));
        assertFalse(matcher.matches(result("src/LoginInterceptor.java", "LoginInterceptor#preHandle", "CONFIG"), evalCase));
    }

    @Test
    void findsFirstGroundTruthRankAndReportsMissing() {
        CodeRagEvalCase evalCase = evalCase();
        List<CodeSearchResult> candidates = List.of(
                result("src/Other.java", "Other#run", "JAVA_METHOD"),
                result("src/LoginInterceptor.java", "LoginInterceptor#preHandle", "JAVA_METHOD"));
        assertEquals(2, matcher.firstMatchRank(candidates, evalCase));
        assertFalse(matcher.hitWithin(candidates, evalCase, 1));
        assertTrue(matcher.hitWithin(candidates, evalCase, 2));
        assertEquals(0, matcher.firstMatchRank(List.of(candidates.get(0)), evalCase));
    }

    @Test
    void supportsMultipleAcceptableGroundTruthKeywords() {
        CodeRagEvalCase evalCase = evalCase();
        evalCase.expectedFileKeywords = List.of("LoginInterceptor", "RefreshTokenInterceptor");
        assertTrue(matcher.matches(result("src/RefreshTokenInterceptor.java", "x", "JAVA_METHOD"), evalCase));
    }

    private CodeRagEvalCase evalCase() {
        CodeRagEvalCase evalCase = new CodeRagEvalCase();
        evalCase.id = "case";
        evalCase.query = "question";
        evalCase.category = "UTIL";
        evalCase.expectedFileKeywords = List.of("LoginInterceptor");
        evalCase.expectedSymbolKeywords = List.of("preHandle");
        evalCase.expectedChunkTypes = List.of("JAVA_METHOD");
        return evalCase;
    }

    private CodeSearchResult result(String file, String symbol, String chunkType) {
        return CodeSearchResult.builder().filePath(file).symbolName(symbol).chunkType(chunkType).build();
    }
}
