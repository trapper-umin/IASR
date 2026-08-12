package com.iasr.benchmark.report;

import com.iasr.benchmark.load.LoadGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Everything measured for one (mode, scenario) pair. */
public record RunResult(
        String mode,
        String scenario,
        long durationMs,
        long dispatched,
        long succeeded,
        long rejected429,
        long serverErrors,
        long clientTimeouts,
        long otherFailures,
        double goodputRps,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double maxMs,
        double sloBreachPercent,
        List<ActuatorSample> actuatorTrace,
        List<String> datasetLines
) {

    /** One periodic observation of the actuators while the run was in flight. */
    public record ActuatorSample(double elapsedSec, int concurrencyLimit, int hikariMaxPool, int inflight) { }

    public static RunResult from(String mode,
                                 String scenario,
                                 long durationMs,
                                 double sloMs,
                                 LoadGenerator.Outcomes outcomes,
                                 List<ActuatorSample> trace,
                                 List<String> datasetLines) {

        double[] latencies = outcomes.successLatenciesMs.stream()
                .mapToDouble(Long::doubleValue)
                .toArray();
        Arrays.sort(latencies);

        long breaches = 0;
        for (double latency : latencies) {
            if (latency > sloMs) {
                breaches++;
            }
        }

        return new RunResult(
                mode,
                scenario,
                durationMs,
                outcomes.dispatched.get(),
                outcomes.succeeded.get(),
                outcomes.rejected429.get(),
                outcomes.serverErrors.get(),
                outcomes.clientTimeouts.get(),
                outcomes.otherFailures.get(),
                round(outcomes.succeeded.get() / (durationMs / 1000.0)),
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99),
                latencies.length == 0 ? 0 : round(latencies[latencies.length - 1]),
                latencies.length == 0 ? 0 : round(100.0 * breaches / latencies.length),
                new ArrayList<>(trace),
                new ArrayList<>(datasetLines));
    }

    /** Share of dispatched requests that got neither a 200 nor a fast 429. */
    public double failureRatePercent() {
        long hardFailures = serverErrors + clientTimeouts + otherFailures;
        return dispatched == 0 ? 0 : round(100.0 * hardFailures / dispatched);
    }

    public double rejectionRatePercent() {
        return dispatched == 0 ? 0 : round(100.0 * rejected429 / dispatched);
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return round(sorted[Math.max(0, Math.min(index, sorted.length - 1))]);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
