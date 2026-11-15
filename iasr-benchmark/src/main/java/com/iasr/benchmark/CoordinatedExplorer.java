package com.iasr.benchmark;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.resource.CoordinatedActionSpace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Behaviour policy that collects transitions for the coordinated learned policy.
 * <p>
 * With probability epsilon it takes the joint action tried least often in the
 * current kind of state; otherwise it follows its anchor. Two anchors give two
 * different pictures of the system: the baseline rules keep the run near where
 * a competent controller lives, while holding lets a perturbed configuration
 * persist long enough for its consequences to be observed.
 */
public final class CoordinatedExplorer implements Controller {
    public enum Anchor { BASELINE, HOLD }

    private final CoordinatedActionSpace space;
    private final Controller baseline;
    private final double epsilon;
    private final long seed;
    private final Anchor anchor;
    private final Random random;
    private final Map<String, Map<String, Long>> visits = new HashMap<>();
    private int activeWindows;

    public CoordinatedExplorer(CoordinatedActionSpace space, Controller baseline, double epsilon, long seed, Anchor anchor) {
        if (!Double.isFinite(epsilon) || epsilon < 0 || epsilon > 1) throw new IllegalArgumentException("epsilon must be in [0,1]");
        this.space = java.util.Objects.requireNonNull(space);
        this.baseline = java.util.Objects.requireNonNull(baseline);
        this.anchor = java.util.Objects.requireNonNull(anchor);
        this.epsilon = epsilon;
        this.seed = seed;
        this.random = new Random(seed);
    }

    public Map<String, Object> parameters() {
        List<Map<String, Object>> resources = new ArrayList<>();
        for (var m : space.managed()) {
            resources.add(Map.of("actuator", m.resource().actuatorName(), "levels",
                    m.levels().stream().map(l -> List.of(l.relative(), l.minStep())).toList()));
        }
        return Map.of("actionSpace", "coordinated-relative-v1", "epsilon", epsilon, "policySeed", seed,
                "anchor", anchor.name().toLowerCase(java.util.Locale.ROOT), "resources", resources);
    }

    @Override
    public ControlAction decide(MetricsSnapshot state, List<Actuator> actuators) {
        // The anchor's own memory must follow every window, also the ones it does not decide.
        ControlAction anchored = baseline.decide(state, actuators);
        double goodput = state.get(MetricNames.GOODPUT_RPS, Double.NaN);
        double rejected = state.get(MetricNames.TIMEOUT_RATE, 0.0);
        boolean active = (Double.isFinite(goodput) && goodput >= 1) || rejected > 0;
        List<int[]> candidates = active ? space.candidates(state, actuators) : List.of();
        if (candidates.isEmpty()) {
            activeWindows = 0;
            return anchored;
        }
        if (++activeWindows < 2) return anchored;
        if (random.nextDouble() >= epsilon) {
            return anchor == Anchor.BASELINE ? anchored : space.action(candidates.get(0), "explore_hold");
        }
        Map<String, Long> counts = visits.computeIfAbsent(bucket(state), ignored -> new HashMap<>());
        int[] current = candidates.get(0);
        long least = Long.MAX_VALUE;
        List<int[]> tied = new ArrayList<>();
        for (int[] candidate : candidates) {
            long seen = counts.getOrDefault(direction(current, candidate), 0L);
            if (seen < least) { least = seen; tied.clear(); }
            if (seen == least) tied.add(candidate);
        }
        int[] chosen = tied.get(random.nextInt(tied.size()));
        counts.merge(direction(current, chosen), 1L, Long::sum);
        return space.action(chosen, "explore_coordinated");
    }

    private String bucket(MetricsSnapshot state) {
        double latency = state.get(MetricNames.LATENCY_P95_MS, Double.NaN);
        StringBuilder bucket = new StringBuilder(!Double.isFinite(latency) ? "missing"
                : latency < 0.7 * space.sloMs() ? "comfortable" : latency <= space.sloMs() ? "within_slo" : "over_slo");
        for (var m : space.managed()) {
            bucket.append('/').append(state.get(m.resource().backlogMetric(), 0.0) > 0 ? "backlog" : "free");
        }
        return bucket.toString();
    }

    private static String direction(int[] current, int[] target) {
        int[] signs = new int[current.length];
        for (int i = 0; i < current.length; i++) signs[i] = target[i] - current[i];
        return Arrays.toString(signs);
    }
}
