package com.iasr.core.guardrail;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies safety guardrails on top of controller decisions.
 * <p>
 * Enforced constraints, in order:
 * <ol>
 *   <li>Cooldown — change suppressed if not enough ticks have elapsed</li>
 *   <li>No increase while SLO is violated, for actuators whose guardrail sets
 *       {@link ActuatorGuardrail#blockIncreaseOnSloViolation()}</li>
 *   <li>Step size limited to {@code maxStep}</li>
 *   <li>Value clamped to {@code [min, max]}</li>
 * </ol>
 */
@Slf4j
public class Guardrails {

    /**
     * Cooldown counter value meaning "this actuator has never been changed",
     * so the first change is always allowed regardless of {@code cooldownTicks}.
     */
    static final int NEVER_CHANGED = Integer.MAX_VALUE;

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
            advanceCooldowns(Set.of());
            // Carry the controller's reasons through even when it asked for
            // nothing: "held, metrics in band" and "held, demand gate" are
            // different facts, and dropping them here would erase the only
            // record of which one happened.
            return raw.reasons().isEmpty() ? ControlAction.NONE : reasonsOnly(raw);
        }

        boolean sloViolated = snapshot.has(MetricNames.LATENCY_P95_MS)
                && snapshot.get(MetricNames.LATENCY_P95_MS) > sloLatencyMs;

        ControlAction.Builder builder = ControlAction.builder();
        Set<String> changedThisTick = new HashSet<>();

        for (Actuator actuator : actuators) {
            String name = actuator.name();
            int current = actuator.currentValue();
            int desired = raw.getOrDefault(name, current);
            ActuatorGuardrail rule = rules.get(name);

            if (rule == null) {
                if (desired != current) {
                    builder.set(name, desired, annotate(raw, name, "applied"));
                    changedThisTick.add(name);
                } else {
                    builder.hold(name, annotate(raw, name, "no_change"));
                }
                continue;
            }

            int delta = desired - current;

            // 1. Cooldown check
            int elapsed = ticksSinceChange.getOrDefault(name, NEVER_CHANGED);
            if (delta != 0 && elapsed < rule.cooldownTicks()) {
                log.debug("Guardrail [{}]: change suppressed, cooldown ({}/{} ticks)",
                        name, elapsed, rule.cooldownTicks());
                builder.hold(name, annotate(raw, name, "suppressed_cooldown"));
                continue;
            }

            // 2. No increase during SLO violation — unless this actuator is
            //    explicitly exempt (see ActuatorGuardrail#blockIncreaseOnSloViolation)
            if (delta > 0 && sloViolated && rule.blockIncreaseOnSloViolation()) {
                log.debug("Guardrail [{}]: increase suppressed, SLO violated (p95={} > {})",
                        name, snapshot.get(MetricNames.LATENCY_P95_MS), sloLatencyMs);
                builder.hold(name, annotate(raw, name, "suppressed_slo_block"));
                continue;
            }

            // 3. Clamp step size — asymmetrically, because growing a resource
            //    too fast can cause an incident while shrinking it is the way
            //    out of one.  A single limit on both directions would throttle
            //    the controller's emergency brake as hard as its accelerator.
            int maxUp = rule.maxStep();
            int maxDown = rule.effectiveMaxStepDown();
            String outcome = "applied";
            if (delta > maxUp) {
                delta = maxUp;
                outcome = "clamped_max_step";
            } else if (delta < -maxDown) {
                delta = -maxDown;
                outcome = "clamped_max_step_down";
            }

            // 4. Clamp absolute value
            int stepped = current + delta;
            int clamped = Math.max(rule.minValue(), Math.min(stepped, rule.maxValue()));
            if (clamped != stepped) {
                outcome = "clamped_bounds";
            }

            if (clamped != current) {
                builder.set(name, clamped, annotate(raw, name, outcome));
                changedThisTick.add(name);
            } else {
                builder.hold(name, annotate(raw, name, "no_change"));
            }
        }

        advanceCooldowns(changedThisTick);
        return builder.build();
    }

    /**
     * Join the controller's reason with what this layer did to it, so a record
     * says both what was intended and what became of the intent.
     */
    private static String annotate(ControlAction raw, String name, String outcome) {
        String decided = raw.reason(name);
        return decided == null ? outcome : decided + "|" + outcome;
    }

    /** Pass reasons through when there is nothing to apply. */
    private static ControlAction reasonsOnly(ControlAction raw) {
        ControlAction.Builder builder = ControlAction.builder();
        raw.reasons().forEach(builder::hold);
        return builder.build();
    }

    /**
     * Advance cooldown counters: reset to 0 for actuators that changed this tick,
     * increment by 1 for all others.  This avoids the off-by-one where a reset-then-
     * increment would shorten the effective cooldown by 1 tick.
     * <p>
     * Actuators that have never been changed stay pinned at {@link #NEVER_CHANGED}
     * so that startup ticks do not accumulate into an artificial cooldown window
     * that blocks the very first change.  The increment saturates for the same reason.
     */
    private void advanceCooldowns(Set<String> changedThisTick) {
        for (String name : rules.keySet()) {
            if (changedThisTick.contains(name)) {
                ticksSinceChange.put(name, 0);
            } else {
                ticksSinceChange.compute(name, (k, v) ->
                        (v == null || v >= NEVER_CHANGED - 1) ? NEVER_CHANGED : v + 1);
            }
        }
    }
}
