package com.iasr.core.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lock-free accumulator of request durations with per-window percentile computation.
 * <p>
 * Concurrent writers call {@link #record(double)} from request threads.
 * The control-loop thread calls {@link #drainAndCompute()} once per tick,
 * which atomically drains the queue and returns per-window statistics.
 * After drain, the buffer is empty for the next window.
 * <p>
 * This solves the fundamental problem with Micrometer's built-in percentiles:
 * they are computed over a <em>decay window</em> (~2 min), not over the IASR
 * control window (Δt, typically 5 s).  Two consecutive 5-second snapshots from
 * the same Micrometer histogram are nearly identical, making state ≈ outcome in
 * the dataset.  This tracker gives truly per-window metrics.
 */
public class WindowedLatencyTracker {

    private final ConcurrentLinkedQueue<Double> observations = new ConcurrentLinkedQueue<>();

    /**
     * Record a single request duration.  Thread-safe, non-blocking.
     *
     * @param durationMs request duration in milliseconds
     */
    public void record(double durationMs) {
        observations.add(durationMs);
    }

    /**
     * Drain all accumulated observations and compute per-window statistics.
     * After this call the tracker is empty for the next window.
     *
     * @return computed statistics, or {@link WindowStats#EMPTY} if no observations
     */
    public WindowStats drainAndCompute() {
        List<Double> drained = new ArrayList<>();
        Double val;
        while ((val = observations.poll()) != null) {
            drained.add(val);
        }
        if (drained.isEmpty()) {
            return WindowStats.EMPTY;
        }

        double[] arr = new double[drained.size()];
        double sum = 0;
        for (int i = 0; i < drained.size(); i++) {
            arr[i] = drained.get(i);
            sum += arr[i];
        }
        Arrays.sort(arr);

        return new WindowStats(
                arr.length,
                sum / arr.length,
                percentile(arr, 0.95),
                percentile(arr, 0.99),
                arr[arr.length - 1]
        );
    }

    private static double percentile(double[] sortedArr, double p) {
        int index = (int) Math.ceil(p * sortedArr.length) - 1;
        return sortedArr[Math.max(0, index)];
    }

    /**
     * Per-window latency statistics.
     */
    public record WindowStats(int count, double meanMs, double p95Ms, double p99Ms, double maxMs) {

        public static final WindowStats EMPTY = new WindowStats(0, 0, 0, 0, 0);

        public boolean hasData() {
            return count > 0;
        }
    }
}
