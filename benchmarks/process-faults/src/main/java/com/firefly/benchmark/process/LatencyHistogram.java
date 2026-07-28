package com.firefly.benchmark.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LatencyHistogram {
    private final List<Long> values = new ArrayList<>();

    public void recordMillis(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("latency must be >= 0");
        }
        values.add(value);
    }

    public int count() {
        return values.size();
    }

    public long maxMillis() {
        if (values.isEmpty()) {
            return 0;
        }
        return Collections.max(values);
    }

    public long percentileMillis(double percentile) {
        if (percentile <= 0 || percentile > 100) {
            throw new IllegalArgumentException("percentile must be in (0, 100]");
        }
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
