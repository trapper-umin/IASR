package com.iasr.policy;

import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.metrics.MetricNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Feature contract v1, identical in order and float32 rounding to fqi_features.py. */
public final class FqiFeatures {
    private FqiFeatures() { }
    public static final List<String> METRICS = List.of("latency_p95_ms", "latency_p99_ms", "latency_mean_ms",
            "service_mean_ms", "goodput_rps", "error_rate", "timeout_rate", "inflight", "queue_length",
            "admission_wait_mean_ms", "hikari_active", "hikari_idle", "hikari_pending", "hikari_acquire_mean_ms",
            "cpu_proc", "gc_pause_p95_ms");
    private static final List<String> EXTRA = List.of("limit_fraction", "pool_fraction", "limit_absolute", "pool_absolute",
            "limit_cooldown", "pool_cooldown", "pool_phase", "elapsed_ratio", "window_seconds", "pool_period");
    private static final Set<String> LATENCIES = Set.of("latency_p95_ms", "latency_p99_ms", "latency_mean_ms",
            "service_mean_ms", "admission_wait_mean_ms", "hikari_acquire_mean_ms", "gc_pause_p95_ms");

    public record Observation(Map<String, Double> state, Map<String, Integer> resourcesBefore,
                              Map<String, Integer> cooldownRemaining, double elapsedSeconds) {
        public Observation {
            state = Collections.unmodifiableMap(new LinkedHashMap<>(state)); // Null is an explicitly missing metric.
            resourcesBefore = Map.copyOf(resourcesBefore);
            cooldownRemaining = Map.copyOf(cooldownRemaining);
        }
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (String metric : METRICS) names.add("w" + i + "." + metric);
            for (String metric : EXTRA) names.add("w" + i + "." + metric);
        }
        for (String name : List.copyOf(names)) names.add("missing." + name);
        return List.copyOf(names);
    }

    public static double[] transform(List<Observation> history, PolicyContext context, double rref,
                                      double[] centers, double[] scales, InferenceBudget budget) {
        if (history.size() != 4 || centers.length != 104 || scales.length != 104 || !Double.isFinite(rref) || rref <= 0) {
            throw new IllegalArgumentException("Invalid feature dimensions or scale");
        }
        List<Double> raw = new ArrayList<>(104);
        for (Observation observation : history) {
            budget.check();
            double limit = required(observation.resourcesBefore().get(JointActionSpace.LIMIT));
            double pool = required(observation.resourcesBefore().get(JointActionSpace.POOL));
            for (String metric : METRICS) {
                Double value = observation.state().get(metric);
                if (value != null) {
                    value = finite(value);
                    if (LATENCIES.contains(metric)) value /= context.sloMs();
                    else if (metric.equals("goodput_rps")) value /= rref;
                    else if (metric.equals("inflight") || metric.equals("queue_length")) value /= Math.max(1, limit);
                    else if (Set.of("hikari_active", "hikari_idle", "hikari_pending").contains(metric)) value /= Math.max(1, pool);
                }
                raw.add(value);
            }
            for (String name : List.of(JointActionSpace.LIMIT, JointActionSpace.POOL)) {
                var rule = context.rules().get(name);
                raw.add((required(observation.resourcesBefore().get(name)) - rule.minValue()) / Math.max(1.0, (double) rule.maxValue() - rule.minValue()));
            }
            raw.add(limit / 100.0); raw.add(pool / 32.0);
            for (String name : List.of(JointActionSpace.LIMIT, JointActionSpace.POOL)) {
                Integer value = observation.cooldownRemaining().get(name);
                raw.add(value == null ? null : value / (double) Math.max(1, context.rules().get(name).cooldownTicks()));
            }
            double tick = required(observation.state().get(MetricNames.CONTROL_TICK));
            if (tick < 0 || tick != Math.floor(tick) || tick >= Long.MAX_VALUE) throw new IllegalArgumentException("Invalid decision tick");
            raw.add(((long) tick % context.poolPeriodTicks()) / (double) Math.max(1, context.poolPeriodTicks() - 1));
            raw.add(finite(observation.elapsedSeconds()) / context.windowSec());
            raw.add(context.windowSec() / 5.0); raw.add(context.poolPeriodTicks() / 5.0);
        }
        double[] features = new double[208];
        for (int i = 0; i < raw.size(); i++) {
            Double value = raw.get(i);
            features[i] = value == null ? 0 : f32((value - centers[i]) / scales[i]);
            features[i + raw.size()] = value == null ? 1 : 0;
        }
        budget.check();
        return features;
    }

    public static double[] withAction(double[] state, JointActionSpace.Delta delta, PolicyContext context) {
        double[] input = java.util.Arrays.copyOf(state, state.length + 2);
        input[state.length] = f32(delta.concurrency() / (double) context.limitStep());
        input[state.length + 1] = f32(delta.pool());
        return input;
    }

    static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite feature");
        return value;
    }

    static double required(Number value) {
        if (value == null) throw new IllegalArgumentException("Missing control context");
        return finite(value.doubleValue());
    }

    static double f32(double value) {
        finite(value);
        if (Math.abs(value) >= 1e30) throw new IllegalArgumentException("Feature outside numeric limits");
        return (double) (float) value;
    }
}
