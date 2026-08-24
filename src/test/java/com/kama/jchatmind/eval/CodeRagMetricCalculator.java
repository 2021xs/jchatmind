package com.kama.jchatmind.eval;

class CodeRagMetricCalculator {

    boolean hitAt(int rank, int topK) {
        return rank > 0 && rank <= topK;
    }

    double reciprocalRank(int rank) {
        return rank <= 0 ? 0.0 : 1.0 / rank;
    }

    double rate(int hits, int total) {
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
