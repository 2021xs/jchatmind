package com.kama.jchatmind.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeRagFailureClassifierTest {
    private final CodeRagFailureClassifier classifier = new CodeRagFailureClassifier();

    @Test
    void classifiesLayeredOutcomesAndExceptionalStates() {
        assertEquals(CodeRagFailureType.SUCCESS, classifier.classify(true, false, false, false, 2, 1));
        assertEquals(CodeRagFailureType.RETRIEVAL_MISS, classifier.classify(true, false, false, false, 0, 0));
        assertEquals(CodeRagFailureType.SELECTOR_MISS, classifier.classify(true, false, false, false, 3, 0));
        assertEquals(CodeRagFailureType.FALLBACK, classifier.classify(true, false, false, true, 3, 1));
        assertEquals(CodeRagFailureType.SELECTOR_ERROR, classifier.classify(true, false, true, false, 3, 0));
        assertEquals(CodeRagFailureType.RETRIEVAL_ERROR, classifier.classify(true, true, false, false, 0, 0));
        assertEquals(CodeRagFailureType.GROUND_TRUTH_INVALID, classifier.classify(false, false, false, false, 0, 0));
    }
}
