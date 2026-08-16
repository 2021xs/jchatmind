package com.kama.jchatmind.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class PercentileCalculator {

    long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        if (percentile <= 0 || percentile > 1) {
            throw new IllegalArgumentException("percentile must be in (0, 1]");
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
