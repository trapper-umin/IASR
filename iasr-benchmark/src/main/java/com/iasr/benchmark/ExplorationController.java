package com.iasr.benchmark;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;

import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

/** Opt-in collector with uniform, balanced or joint-focused exploration of admissible actions. */
public final class ExplorationController implements Controller {
    private final JointActionSpace space;
    private final Controller baseline;
    private final double epsilon;
    private final long seed;
    private final Random random;
    private int activeWindows;
    public enum Sampling { UNIFORM, BALANCED, JOINT }
    private final Sampling sampling;
    private final double sloMs;
    private final Map<String, Map<JointActionSpace.Delta, Long>> visits = new HashMap<>();

    public ExplorationController(JointActionSpace space, Controller baseline, double epsilon, long seed) {
        this(space, baseline, epsilon, seed, Sampling.UNIFORM, 150);
    }

    public ExplorationController(JointActionSpace space, Controller baseline, double epsilon, long seed,
                                 Sampling sampling, double sloMs) {
        if (!Double.isFinite(sloMs) || sloMs <= 0) throw new IllegalArgumentException("Invalid SLO");
        this.sampling = java.util.Objects.requireNonNull(sampling);
        this.sloMs = sloMs;
        if (!Double.isFinite(epsilon) || epsilon < 0 || epsilon > 1) throw new IllegalArgumentException("epsilon must be in [0,1]");
        this.space = java.util.Objects.requireNonNull(space);
        this.baseline = java.util.Objects.requireNonNull(baseline);
        this.epsilon = epsilon;
        this.seed = seed;
        this.random = new Random(seed);
    }

    public Map<String, Object> parameters() {
        return Map.of("actionSpaceVersion", JointActionSpace.VERSION, "epsilon", epsilon, "policySeed", seed,
                "limitStep", space.limitStep(), "poolPeriodTicks", space.poolPeriodTicks(),
                "historyWindows", 4, "nonExploringAction", "hold", "sampling", sampling.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public ControlAction decide(MetricsSnapshot state, List<Actuator> actuators) {
        double goodput = state.get(MetricNames.GOODPUT_RPS);
        double latency = state.get(MetricNames.LATENCY_P95_MS);
        List<JointActionSpace.Delta> candidates = space.candidates(state, actuators);
        if (!Double.isFinite(goodput) || goodput < 1 || !Double.isFinite(latency) || latency <= 0 || candidates.isEmpty()) {
            activeWindows = 0;
            return baseline.decide(state, actuators);
        }
        if (++activeWindows < 4) return baseline.decide(state, actuators);
        boolean exploreTick = sampling != Sampling.JOINT
                || (long) state.get(MetricNames.CONTROL_TICK) % space.poolPeriodTicks() == 0;
        if (exploreTick && random.nextDouble() < epsilon) {
            if (sampling == Sampling.UNIFORM) {
                return space.action(candidates.get(random.nextInt(candidates.size())), actuators, "explore_uniform");
            }
            if (sampling == Sampling.JOINT) {
                var joint = candidates.stream().filter(delta -> delta.concurrency() != 0 && delta.pool() != 0).toList();
                if (!joint.isEmpty()) candidates = joint;
            }
            String bucket = bucket(state, actuators);
            Map<JointActionSpace.Delta, Long> counts = visits.computeIfAbsent(bucket, ignored -> new HashMap<>());
            long least = candidates.stream().mapToLong(a -> counts.getOrDefault(a, 0L)).min().orElseThrow();
            List<JointActionSpace.Delta> tied = new ArrayList<>();
            for (var candidate : candidates) if (counts.getOrDefault(candidate, 0L) == least) tied.add(candidate);
            var chosen = tied.get(random.nextInt(tied.size()));
            counts.merge(chosen, 1L, Long::sum);
            return space.action(chosen, actuators, sampling == Sampling.JOINT ? "explore_joint" : "explore_balanced");
        }
        return candidates.contains(JointActionSpace.HOLD)
                ? space.action(JointActionSpace.HOLD, actuators, "explore_hold")
                : baseline.decide(state, actuators);
    }

    private String bucket(MetricsSnapshot state, List<Actuator> actuators) {
        double latency = state.get(MetricNames.LATENCY_P95_MS);
        double pending = state.get(MetricNames.HIKARI_PENDING);
        double inflight = state.get(MetricNames.INFLIGHT);
        int limit = actuators.stream().filter(a -> a.name().equals(JointActionSpace.LIMIT))
                .findFirst().orElseThrow().currentValue();
        return (latency < 0.7 * sloMs ? "comfortable" : latency <= sloMs ? "within_slo" : "over_slo")
                + "/" + (!Double.isFinite(pending) ? "missing" : pending > 0 ? "pending" : "no_pending")
                + "/" + (!Double.isFinite(inflight) ? "missing" : inflight >= 0.9 * Math.max(1, limit) ? "binding" : "headroom")
                + "/" + ((long) state.get(MetricNames.CONTROL_TICK) % space.poolPeriodTicks() == 0 ? "pool_tick" : "limit_tick");
    }
}
