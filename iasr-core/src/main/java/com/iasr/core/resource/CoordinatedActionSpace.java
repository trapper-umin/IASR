package com.iasr.core.resource;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The joint decisions available to a coordinated policy over any number of
 * soft resources.
 * <p>
 * Each managed resource contributes the targets its {@link ActionLevel}s reach
 * from the current capacity; a joint action picks one target per resource.
 * Targets that the guardrails would refuse — a change during cooldown, growth
 * while the latency objective is violated, a value outside the bounds — are
 * never offered, so a policy does not waste a decision on a move that cannot
 * happen, and what it proposes is what gets applied.
 * <p>
 * The space is the single definition shared by data collection and by the
 * learned policy: both enumerate the same candidates from the same state.
 */
public final class CoordinatedActionSpace {

    /** A resource under coordinated control, its safety rule and its moves. */
    public record Managed(SoftResource resource, ActuatorGuardrail rule, List<ActionLevel> levels) {
        public Managed {
            levels = List.copyOf(levels);
            if (!resource.actuatorName().equals(rule.actuatorName())) {
                throw new IllegalArgumentException("Guardrail does not belong to the resource");
            }
            if (!levels.contains(ActionLevel.HOLD)) throw new IllegalArgumentException("Levels must include hold");
        }
    }

    private final List<Managed> managed;
    private final double sloMs;

    public CoordinatedActionSpace(List<Managed> managed, double sloMs) {
        this.managed = List.copyOf(managed);
        if (this.managed.isEmpty() || !Double.isFinite(sloMs) || sloMs <= 0) {
            throw new IllegalArgumentException("Invalid coordinated action space");
        }
        Set<String> names = new LinkedHashSet<>();
        for (Managed m : this.managed) {
            if (!names.add(m.resource().actuatorName())) throw new IllegalArgumentException("Duplicate resource");
        }
        this.sloMs = sloMs;
    }

    public List<Managed> managed() { return managed; }
    public double sloMs() { return sloMs; }

    /** Current capacities in managed order, or {@code null} if an actuator is missing. */
    public int[] current(List<Actuator> actuators) {
        int[] values = new int[managed.size()];
        for (int i = 0; i < values.length; i++) {
            Actuator actuator = find(actuators, managed.get(i).resource().actuatorName());
            if (actuator == null) return null;
            values[i] = actuator.currentValue();
        }
        return values;
    }

    /**
     * Distinct targets one resource may take now. The current value is always
     * first: holding is always possible.
     */
    public List<Integer> targets(int index, MetricsSnapshot state, List<Actuator> actuators) {
        Managed m = managed.get(index);
        Actuator actuator = find(actuators, m.resource().actuatorName());
        if (actuator == null) return List.of();
        int current = actuator.currentValue();
        return targets(m, current, Math.max(m.rule().minValue(), actuator.minValue()),
                Math.min(m.rule().maxValue(), actuator.maxValue()),
                state.get(MetricNames.cooldownRemaining(m.resource().actuatorName()), 0.0),
                state.get(MetricNames.LATENCY_P95_MS, Double.NaN), sloMs);
    }

    /** Pure form of {@link #targets(int, MetricsSnapshot, List)}, mirrored by the training code. */
    public static List<Integer> targets(Managed m, int current, int low, int high,
                                        double cooldownRemaining, double latencyP95Ms, double sloMs) {
        Set<Integer> result = new LinkedHashSet<>();
        result.add(current);
        if (cooldownRemaining > 0 || !Double.isFinite(cooldownRemaining)) return List.copyOf(result);
        boolean growthBlocked = m.rule().blockIncreaseOnSloViolation()
                && !(Double.isFinite(latencyP95Ms) && latencyP95Ms <= sloMs);
        for (ActionLevel level : m.levels()) {
            int delta = level.delta(current);
            if (delta == 0 || (delta > 0 && growthBlocked)) continue;
            delta = Math.max(-m.rule().effectiveMaxStepDown(), Math.min(m.rule().maxStep(), delta));
            int target = Math.max(low, Math.min(high, current + delta));
            if (delta > 0 ? target > current : target < current) result.add(target);
        }
        return List.copyOf(result);
    }

    /**
     * What the guardrails would make of a requested configuration: the same
     * cooldown, growth block, step and bound rules, in the same order. Used to
     * know in advance where the rule-based controller's proposal will land.
     */
    public int[] admissible(java.util.Map<String, Integer> desired, MetricsSnapshot state, List<Actuator> actuators) {
        int[] result = new int[managed.size()];
        for (int i = 0; i < result.length; i++) {
            Managed m = managed.get(i);
            String name = m.resource().actuatorName();
            Actuator actuator = find(actuators, name);
            if (actuator == null) return null;
            int current = actuator.currentValue();
            result[i] = clamp(m.rule(), current, desired.getOrDefault(name, current),
                    state.get(MetricNames.cooldownRemaining(name), 0.0), state.get(MetricNames.LATENCY_P95_MS, Double.NaN), sloMs);
        }
        return result;
    }

    /** Pure form of the guardrail chain for one resource; mirrored by the training code. */
    public static int clamp(ActuatorGuardrail rule, int current, int desired, double cooldownRemaining,
                            double latencyP95Ms, double sloMs) {
        long delta = (long) desired - current;
        if (delta == 0) return current;
        if (!(cooldownRemaining <= 0)) return current;
        if (delta > 0 && rule.blockIncreaseOnSloViolation() && Double.isFinite(latencyP95Ms) && latencyP95Ms > sloMs) {
            return current;
        }
        delta = Math.max(-rule.effectiveMaxStepDown(), Math.min(rule.maxStep(), delta));
        return (int) Math.max(rule.minValue(), Math.min(rule.maxValue(), current + delta));
    }

    /** Every joint action available now; the first is "hold everything". */
    public List<int[]> candidates(MetricsSnapshot state, List<Actuator> actuators) {
        List<int[]> joint = new ArrayList<>();
        joint.add(new int[0]);
        for (int i = 0; i < managed.size(); i++) {
            List<Integer> options = targets(i, state, actuators);
            if (options.isEmpty()) return List.of();
            List<int[]> extended = new ArrayList<>(joint.size() * options.size());
            for (int[] prefix : joint) {
                for (int option : options) {
                    int[] next = java.util.Arrays.copyOf(prefix, prefix.length + 1);
                    next[prefix.length] = option;
                    extended.add(next);
                }
            }
            joint = extended;
        }
        return joint;
    }

    /** Turn chosen targets into a control action carrying one reason for every resource. */
    public ControlAction action(int[] targets, String reason) {
        if (targets.length != managed.size()) throw new IllegalArgumentException("One target per managed resource");
        ControlAction.Builder action = ControlAction.builder();
        for (int i = 0; i < targets.length; i++) action.set(managed.get(i).resource().actuatorName(), targets[i], reason);
        return action.build();
    }

    private static Actuator find(List<Actuator> actuators, String name) {
        for (Actuator actuator : actuators) if (name.equals(actuator.name())) return actuator;
        return null;
    }
}
