package com.iasr.policy;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;

import java.util.List;
import java.util.Map;

public record PolicyContext(double sloMs, double windowSec, int poolPeriodTicks,
                            Map<String, ActuatorGuardrail> rules) {
    public PolicyContext {
        rules = Map.copyOf(rules);
        if (!rules.keySet().containsAll(List.of(JointActionSpace.LIMIT, JointActionSpace.POOL))
                || rules.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().actuatorName()))) {
            throw new IllegalArgumentException("Invalid resource rules");
        }
        new JointActionSpace(List.copyOf(rules.values()), sloMs, windowSec, poolPeriodTicks);
    }

    public int limitStep() { return (int) Math.max(1, Math.round(windowSec)); }

    public JointActionSpace actionSpace() {
        return new JointActionSpace(List.copyOf(rules.values()), sloMs, windowSec, poolPeriodTicks);
    }

    public List<JointActionSpace.Delta> candidates(FqiFeatures.Observation observation) {
        MetricsSnapshot.Builder snapshot = MetricsSnapshot.builder();
        observation.state().forEach((key, value) -> { if (value != null) snapshot.put(key, value); });
        observation.cooldownRemaining().forEach((key, value) -> snapshot.put(MetricNames.cooldownRemaining(key), value));
        List<Actuator> resources = List.of(resource(JointActionSpace.LIMIT, observation), resource(JointActionSpace.POOL, observation));
        return actionSpace().candidates(snapshot.build(), resources);
    }

    private Actuator resource(String name, FqiFeatures.Observation observation) {
        ActuatorGuardrail rule = rules.get(name);
        int value = observation.resourcesBefore().get(name);
        return new Actuator() {
            public String name() { return name; }
            public int currentValue() { return value; }
            public int minValue() { return rule.minValue(); }
            public int maxValue() { return rule.maxValue(); }
            public void apply(int ignored) { throw new UnsupportedOperationException("Read-only observation"); }
        };
    }
}
