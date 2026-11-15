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

    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();

    /**
     * Latency objective used to count how many successful responses of a
     * window met it. Non-positive means "no objective": every response counts.
     */
    private volatile double sloThresholdMs;

    /** Set the latency objective for {@link WindowStats#withinSloFraction()}. */
    public void setSloThresholdMs(double sloThresholdMs) {
        this.sloThresholdMs = sloThresholdMs;
    }

    private record Observation(double totalMs, double serviceMs) { }

    /**
     * Record a single request duration.  Thread-safe, non-blocking.
     *
     * @param durationMs request duration in milliseconds
     */
    public void record(double durationMs) {
        record(durationMs, 0);
    }

    /**
     * Record a successful request, pairing its service duration and admission
     * wait before computing any percentiles. Percentiles cannot be added.
     */
    public void record(double serviceDurationMs, double admissionWaitMs) {
        observations.add(new Observation(serviceDurationMs + admissionWaitMs, serviceDurationMs));
    }

    /**
     * Drain all accumulated observations and compute per-window statistics.
     * After this call the tracker is empty for the next window.
     *
     * @return computed statistics, or {@link WindowStats#EMPTY} if no observations
     */
    public WindowStats drainAndCompute() {
        List<Observation> drained = new ArrayList<>();
        Observation val;
        while ((val = observations.poll()) != null) {
            drained.add(val);
        }
        if (drained.isEmpty()) {
            return WindowStats.EMPTY;
        }

        double[] arr = new double[drained.size()];
        double sum = 0;
        double serviceSum = 0;
        for (int i = 0; i < drained.size(); i++) {
            arr[i] = drained.get(i).totalMs();
            sum += arr[i];
            serviceSum += drained.get(i).serviceMs();
        }
        Arrays.sort(arr);
        double threshold = sloThresholdMs;
        int within = arr.length;
        if (threshold > 0) {
            within = 0;
            while (within < arr.length && arr[within] <= threshold) within++;
        }

        return new WindowStats(
                arr.length,
                sum / arr.length,
                percentile(arr, 0.95),
                percentile(arr, 0.99),
                arr[arr.length - 1],
                serviceSum / arr.length,
                (double) within / arr.length
        );
    }

    private static double percentile(double[] sortedArr, double p) {
        int index = (int) Math.ceil(p * sortedArr.length) - 1;
        return sortedArr[Math.max(0, index)];
    }

    /**
     * Per-window latency statistics.
     */
    public record WindowStats(int count, double meanMs, double p95Ms, double p99Ms,
                              double maxMs, double serviceMeanMs, double withinSloFraction) {

        /** Compatibility for callers that do not track an objective. */
        public WindowStats(int count, double meanMs, double p95Ms, double p99Ms,
                           double maxMs, double serviceMeanMs) {
            this(count, meanMs, p95Ms, p99Ms, maxMs, serviceMeanMs, 1.0);
        }

        /** Compatibility for callers supplying durations without a separate wait. */
        public WindowStats(int count, double meanMs, double p95Ms, double p99Ms, double maxMs) {
            this(count, meanMs, p95Ms, p99Ms, maxMs, meanMs);
        }

        public static final WindowStats EMPTY = new WindowStats(0, 0, 0, 0, 0);

        public boolean hasData() {
            return count > 0;
        }
    }
}
