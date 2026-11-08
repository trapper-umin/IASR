package com.iasr.policy;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Experimental controller; all proposals still pass through the engine's guardrails. */
public final class LearnedController implements Controller {
    private final FqiModel model;
    private final PolicyContext context;
    private final Controller baseline;
    private final long budgetNanos;
    private final LongSupplier clock;
    private final ArrayDeque<FqiFeatures.Observation> history = new ArrayDeque<>(4);
    private final Map<String, Long> fallbackCounts = new LinkedHashMap<>();
    private long previousTick = -1, decisions, modeled, totalNanos, maxNanos, scored;

    public LearnedController(FqiModel model, PolicyContext context, Controller baseline, long budgetNanos) {
        this(model, context, baseline, budgetNanos, System::nanoTime);
    }

    LearnedController(FqiModel model, PolicyContext context, Controller baseline, long budgetNanos, LongSupplier clock) {
        if (budgetNanos <= 0) throw new IllegalArgumentException("Inference budget must be positive");
        this.model = java.util.Objects.requireNonNull(model);
        this.context = java.util.Objects.requireNonNull(context);
        this.baseline = java.util.Objects.requireNonNull(baseline);
        this.budgetNanos = budgetNanos;
        this.clock = clock;
    }

    @Override
    public synchronized ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators) {
        decisions++;
        // Keep the baseline's own temporal state current even when the model is selected.
        ControlAction alternative = baseline.decide(snapshot, actuators);
        try {
            FqiFeatures.Observation observation = observe(snapshot, actuators);
            long tick = (long) (double) observation.state().get(MetricNames.CONTROL_TICK);
            if (tick != previousTick + 1) history.clear();
            previousTick = tick;
            if (history.size() == 4) history.removeFirst();
            history.addLast(observation);
            if (history.size() < 4) return fallback(alternative, actuators, "short_history");
        } catch (IllegalArgumentException e) {
            history.clear(); previousTick = -1;
            return fallback(alternative, actuators, "invalid_observation");
        }

        long started = clock.getAsLong();
        try {
            InferenceBudget budget = new InferenceBudget(budgetNanos, clock);
            var decision = model.decide(List.copyOf(history), context, budget);
            if (decision.fallback()) return fallback(alternative, actuators, "no_empirical_support");
            if (!context.actionSpace().candidates(snapshot, actuators).contains(decision.action())) {
                return fallback(alternative, actuators, "invalid_action");
            }
            budget.check();
            modeled++;
            return context.actionSpace().action(decision.action(), actuators, "learned_policy");
        } catch (InferenceBudget.Exceeded e) {
            return fallback(alternative, actuators, "budget");
        } catch (RuntimeException e) {
            return fallback(alternative, actuators, "model_error");
        } finally {
            long elapsed = Math.max(0, clock.getAsLong() - started);
            scored++; totalNanos += elapsed; maxNanos = Math.max(maxNanos, elapsed);
        }
    }

    private FqiFeatures.Observation observe(MetricsSnapshot snapshot, List<Actuator> actuators) {
        Map<String, Double> state = new LinkedHashMap<>();
        for (String key : FqiFeatures.METRICS) {
            double value = snapshot.get(key, Double.NaN);
            state.put(key, Double.isFinite(value) ? value : null);
        }
        double tick = snapshot.get(MetricNames.CONTROL_TICK, Double.NaN);
        double window = snapshot.get(MetricNames.CONTROL_WINDOW_SEC, Double.NaN);
        double elapsed = snapshot.get(MetricNames.OBSERVATION_ELAPSED_SEC, Double.NaN);
        if (!Double.isFinite(tick) || tick < 0 || tick >= Long.MAX_VALUE || tick != Math.floor(tick)
                || window != context.windowSec() || !Double.isFinite(elapsed) || elapsed <= 0
                || Math.abs(elapsed - window) > Math.max(0.1, 0.25 * window)) {
            throw new IllegalArgumentException("Invalid control interval");
        }
        state.put(MetricNames.CONTROL_TICK, tick); state.put(MetricNames.CONTROL_WINDOW_SEC, window);
        Map<String, Integer> resources = new LinkedHashMap<>(), cooldowns = new LinkedHashMap<>();
        for (String name : List.of(JointActionSpace.LIMIT, JointActionSpace.POOL)) {
            List<Actuator> found = actuators.stream().filter(a -> a.name().equals(name)).toList();
            if (found.size() != 1) throw new IllegalArgumentException("Missing or duplicate actuator");
            Actuator actuator = found.get(0);
            int value = actuator.currentValue();
            var rule = context.rules().get(name);
            if (value < Math.max(rule.minValue(), actuator.minValue()) || value > Math.min(rule.maxValue(), actuator.maxValue())) {
                throw new IllegalArgumentException("Resource outside bounds");
            }
            double remaining = snapshot.get(MetricNames.cooldownRemaining(name), Double.NaN);
            if (!Double.isFinite(remaining) || remaining < 0 || remaining > rule.cooldownTicks() || remaining != Math.floor(remaining)) {
                throw new IllegalArgumentException("Invalid cooldown");
            }
            resources.put(name, value); cooldowns.put(name, (int) remaining);
        }
        return new FqiFeatures.Observation(state, resources, cooldowns, elapsed);
    }

    private ControlAction fallback(ControlAction action, List<Actuator> actuators, String reason) {
        fallbackCounts.merge(reason, 1L, Long::sum);
        var result = ControlAction.builder().also(action.desiredValues());
        for (Actuator actuator : actuators) result.reason(actuator.name(), "learned_fallback_" + reason
                + (action.reason(actuator.name()) == null ? "" : ":" + action.reason(actuator.name())));
        return result.build();
    }

    public synchronized Map<String, Object> statistics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisions", decisions); result.put("modelDecisions", modeled);
        result.put("fallbacks", Map.copyOf(fallbackCounts)); result.put("inferenceAttempts", scored);
        result.put("meanInferenceMs", scored == 0 ? 0 : totalNanos / (double) scored / 1e6);
        result.put("maxInferenceMs", maxNanos / 1e6);
        return result;
    }

    public Map<String, Object> parameters() {
        return Map.of("modelSha256", model.sha256(), "deploymentAllowed", false, "poolPeriodTicks", context.poolPeriodTicks(),
                "inferenceBudgetMs", budgetNanos / 1e6, "featureVersion", 1, "treeCount", model.treeCount(),
                "modelVersion", model.modelVersion(), "algorithm", model.algorithm());
    }
}
