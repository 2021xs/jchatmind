package com.kama.jchatmind.eval;

class CodeRagFailureClassifier {

    CodeRagFailureType classify(boolean validGroundTruth,
                                boolean retrievalError,
                                boolean selectorError,
                                boolean fallback,
                                int rawRank,
                                int selectedRank) {
        if (!validGroundTruth) {
            return CodeRagFailureType.GROUND_TRUTH_INVALID;
        }
        if (retrievalError) {
            return CodeRagFailureType.RETRIEVAL_ERROR;
        }
        if (selectorError) {
            return CodeRagFailureType.SELECTOR_ERROR;
        }
        if (fallback) {
            return CodeRagFailureType.FALLBACK;
        }
        if (selectedRank > 0) {
            return CodeRagFailureType.SUCCESS;
        }
        if (rawRank <= 0) {
            return CodeRagFailureType.RETRIEVAL_MISS;
        }
        return CodeRagFailureType.SELECTOR_MISS;
    }
}
