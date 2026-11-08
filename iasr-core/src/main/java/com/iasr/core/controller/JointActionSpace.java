package com.iasr.core.controller;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Discrete joint actions shared by experimental collectors and future learned policies. */
public final class JointActionSpace {
    public static final int VERSION = 1;
    public static final String LIMIT = ConcurrencyLimiter.ACTUATOR_NAME;
    public static final String POOL = HikariPoolActuator.ACTUATOR_NAME;
    public record Delta(int concurrency, int pool) { }
    public static final Delta HOLD = new Delta(0, 0);

    private final Map<String, ActuatorGuardrail> rules;
    private final double sloMs;
    private final int limitStep;
    private final int poolPeriodTicks;

    public JointActionSpace(List<ActuatorGuardrail> rules, double sloMs, double windowSec, int poolPeriodTicks) {
        if (!Double.isFinite(sloMs) || sloMs <= 0 || !Double.isFinite(windowSec) || windowSec <= 0
                || windowSec > Integer.MAX_VALUE || poolPeriodTicks < 1) {
            throw new IllegalArgumentException("Invalid joint action configuration");
        }
        this.rules = rules.stream().collect(Collectors.toUnmodifiableMap(ActuatorGuardrail::actuatorName, Function.identity()));
        if (!this.rules.keySet().containsAll(List.of(LIMIT, POOL))) {
            throw new IllegalArgumentException("Joint actions require guards for concurrency and pool");
        }
        this.sloMs = sloMs;
        this.limitStep = (int) Math.max(1, Math.round(windowSec));
        this.poolPeriodTicks = poolPeriodTicks;
    }

    public int limitStep() { return limitStep; }
    public int poolPeriodTicks() { return poolPeriodTicks; }

    public List<Delta> candidates(MetricsSnapshot state, List<Actuator> actuators) {
        double tick = state.get(MetricNames.CONTROL_TICK);
        if (!Double.isFinite(tick) || tick < 0 || tick != Math.floor(tick)) return List.of();
        Map<String, Actuator> byName = actuators.stream().collect(Collectors.toMap(Actuator::name, Function.identity()));
        if (!byName.keySet().containsAll(List.of(LIMIT, POOL))) return List.of();
        List<Delta> candidates = new ArrayList<>();
        for (int dl : new int[]{-limitStep, 0, limitStep}) {
            for (int dp : new int[]{-1, 0, 1}) {
                if (dp != 0 && (long) tick % poolPeriodTicks != 0) continue;
                if (allowed(LIMIT, dl, state, byName.get(LIMIT)) && allowed(POOL, dp, state, byName.get(POOL))) {
                    candidates.add(new Delta(dl, dp));
                }
            }
        }
        return List.copyOf(candidates);
    }

    private boolean allowed(String name, int delta, MetricsSnapshot state, Actuator actuator) {
        ActuatorGuardrail rule = rules.get(name);
        long target = (long) actuator.currentValue() + delta;
        if (target < Math.max(rule.minValue(), actuator.minValue())
                || target > Math.min(rule.maxValue(), actuator.maxValue())) return false;
        if (delta == 0) return true;
        double cooldown = state.get(MetricNames.cooldownRemaining(name));
        if (!Double.isFinite(cooldown) || cooldown != 0) return false;
        if (delta > rule.maxStep() || -(long) delta > rule.effectiveMaxStepDown()) return false;
        double latency = state.get(MetricNames.LATENCY_P95_MS);
        return delta <= 0 || !rule.blockIncreaseOnSloViolation()
                || (Double.isFinite(latency) && latency <= sloMs);
    }

    public ControlAction action(Delta delta, List<Actuator> actuators, String reason) {
        ControlAction.Builder action = ControlAction.builder();
        for (Actuator actuator : actuators) {
            if (LIMIT.equals(actuator.name())) action.set(LIMIT, Math.addExact(actuator.currentValue(), delta.concurrency()), reason);
            if (POOL.equals(actuator.name())) action.set(POOL, Math.addExact(actuator.currentValue(), delta.pool()), reason);
        }
        return action.build();
    }
}
