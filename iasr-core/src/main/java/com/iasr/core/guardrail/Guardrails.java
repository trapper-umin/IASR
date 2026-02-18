package com.iasr.core.guardrail;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies safety guardrails on top of controller decisions.
 * <p>
 * Enforced constraints:
 * <ol>
 *   <li>Value clamped to {@code [min, max]}</li>
 *   <li>Step size limited to {@code maxStep}</li>
 *   <li>Cooldown — change suppressed if not enough ticks have elapsed</li>
 *   <li>No increase while SLO is violated (latency p95 &gt; SLO target)</li>
 * </ol>
 */
@Slf4j
public class Guardrails {

    private final Map<String, ActuatorGuardrail> rules;
    private final double sloLatencyMs;

    /** Per-actuator tick counter since last change. */
    private final ConcurrentHashMap<String, Integer> ticksSinceChange = new ConcurrentHashMap<>();

    /**
     * @param guardrailList per-actuator guardrail configs
     * @param sloLatencyMs  SLO target latency p95 in milliseconds
     */
    public Guardrails(List<ActuatorGuardrail> guardrailList, double sloLatencyMs) {
        this.sloLatencyMs = sloLatencyMs;
        this.rules = new HashMap<>();
        for (ActuatorGuardrail g : guardrailList) {
            rules.put(g.actuatorName(), g);
        }
    }

    /**
     * Filter and clamp the controller's raw action.
     *
     * @param raw       the action produced by the controller
     * @param actuators registered actuators (to read current values)
     * @param snapshot  current metrics (for SLO violation check)
     * @return a new action with guardrail-safe values; may be empty
     */
    public ControlAction enforce(ControlAction raw, List<Actuator> actuators, MetricsSnapshot snapshot) {
        if (raw.isEmpty()) {
            incrementAllCooldowns();
            return ControlAction.NONE;
        }

        boolean sloViolated = snapshot.has(MetricNames.LATENCY_P95_MS)
                && snapshot.get(MetricNames.LATENCY_P95_MS) > sloLatencyMs;

        ControlAction.Builder builder = ControlAction.builder();

        for (Actuator actuator : actuators) {
            String name = actuator.name();
            int desired = raw.getOrDefault(name, actuator.currentValue());
            int current = actuator.currentValue();
            ActuatorGuardrail rule = rules.get(name);

            if (rule == null) {
                // No guardrail configured — pass through unchanged
                if (desired != current) {
                    builder.set(name, desired);
                }
                continue;
            }

            int delta = desired - current;

            // 1. Cooldown check
            int elapsed = ticksSinceChange.getOrDefault(name, Integer.MAX_VALUE);
            if (delta != 0 && elapsed < rule.cooldownTicks()) {
                log.debug("Guardrail [{}]: change suppressed, cooldown ({}/{} ticks)",
                        name, elapsed, rule.cooldownTicks());
                continue;
            }

            // 2. No increase during SLO violation
            if (delta > 0 && sloViolated) {
                log.debug("Guardrail [{}]: increase suppressed, SLO violated (p95={} > {})",
                        name, snapshot.get(MetricNames.LATENCY_P95_MS), sloLatencyMs);
                continue;
            }

            // 3. Clamp step size
            if (Math.abs(delta) > rule.maxStep()) {
                delta = delta > 0 ? rule.maxStep() : -rule.maxStep();
            }

            // 4. Clamp absolute value
            int clamped = Math.max(rule.minValue(), Math.min(current + delta, rule.maxValue()));

            if (clamped != current) {
                builder.set(name, clamped);
                ticksSinceChange.put(name, 0);
            }
        }

        incrementAllCooldowns();
        return builder.build();
    }

    private void incrementAllCooldowns() {
        for (String name : rules.keySet()) {
            ticksSinceChange.merge(name, 1, Integer::sum);
        }
    }
}
