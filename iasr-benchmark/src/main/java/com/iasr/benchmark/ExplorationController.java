package com.iasr.benchmark;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** Opt-in benchmark collector: epsilon-uniform admissible actions, otherwise hold. */
public final class ExplorationController implements Controller {
    private final JointActionSpace space;
    private final Controller baseline;
    private final double epsilon;
    private final long seed;
    private final Random random;
    private int activeWindows;

    public ExplorationController(JointActionSpace space, Controller baseline, double epsilon, long seed) {
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
                "historyWindows", 4, "nonExploringAction", "hold");
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
        if (random.nextDouble() < epsilon) {
            return space.action(candidates.get(random.nextInt(candidates.size())), actuators, "explore_uniform");
        }
        return candidates.contains(JointActionSpace.HOLD)
                ? space.action(JointActionSpace.HOLD, actuators, "explore_hold")
                : baseline.decide(state, actuators);
    }
}
