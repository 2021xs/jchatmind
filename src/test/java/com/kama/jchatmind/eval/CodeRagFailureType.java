package com.kama.jchatmind.eval;

enum CodeRagFailureType {
    SUCCESS,
    RETRIEVAL_MISS,
    SELECTOR_MISS,
    FALLBACK,
    SELECTOR_ERROR,
    RETRIEVAL_ERROR,
    GROUND_TRUTH_INVALID
}
