package com.iasr.policy.coordinated;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.resource.CoordinatedActionSpace;
import com.iasr.core.resource.SoftResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Coordinated control of several soft resources by a learned policy.
 * <p>
 * Each window the controller describes the state of every managed resource
 * with the same few signals, enumerates the joint moves the guardrails would
 * accept and compares each with the configuration the rule-based controller is
 * about to produce. It departs from the rules only where the learned
 * action-value function is confidently better (see
 * {@link PolicyModel#choose}). The decision is joint: the value of growing the pool depends on what happens
 * to the admission limit in the same step, and the policy scores the
 * combination rather than each resource on its own.
 * <p>
 * Learning never removes the safety net. The policy only chooses among moves
 * the guardrails allow; it only uses moves that were tried in similar states
 * when the model was trained; and whenever it cannot decide — too little
 * history, an irregular window, a model error, an exceeded time budget — the
 * rule-based baseline decides instead, and the reason is
 * recorded with the action.
 */
public final class CoordinatedController implements Controller {
    private final PolicyModel model;
    private final CoordinatedActionSpace space;
    private final PolicyFeatures features;
    private final Controller baseline;
    private final long budgetNanos;
    private final LongSupplier clock;
    private final Map<String, Long> fallbacks = new LinkedHashMap<>();
    private long previousTick = -1, decisions, modelDecisions, deviations, moves, jointMoves, totalNanos, maxNanos;

    public CoordinatedController(PolicyModel model, List<ActuatorGuardrail> rules, double sloMs,
                                 Controller baseline, long budgetNanos) {
        this(model, rules, sloMs, baseline, budgetNanos, System::nanoTime);
    }

    CoordinatedController(PolicyModel model, List<ActuatorGuardrail> rules, double sloMs,
                          Controller baseline, long budgetNanos, LongSupplier clock) {
        if (budgetNanos <= 0) throw new IllegalArgumentException("Inference budget must be positive");
        this.model = java.util.Objects.requireNonNull(model);
        this.baseline = java.util.Objects.requireNonNull(baseline);
        this.budgetNanos = budgetNanos;
        this.clock = clock;
        List<CoordinatedActionSpace.Managed> managed = new ArrayList<>();
        List<SoftResource> signals = new ArrayList<>();
        for (PolicyModel.Resource resource : model.resources()) {
            ActuatorGuardrail rule = rules.stream()
                    .filter(r -> r.actuatorName().equals(resource.signals().actuatorName())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The policy manages [" + resource.signals().actuatorName() + "] but no guardrail is configured for it"));
            managed.add(new CoordinatedActionSpace.Managed(resource.signals(), rule, resource.levels()));
            signals.add(resource.signals());
        }
        this.space = new CoordinatedActionSpace(managed, sloMs);
        this.features = new PolicyFeatures(signals, sloMs, model.smoothing(), model.ratioCap());
    }

    @Override
    public synchronized ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators) {
        decisions++;
        // The baseline keeps its own memory of recent windows; it must see every one of them.
        ControlAction alternative = baseline.decide(snapshot, actuators);
        double tick = snapshot.get(MetricNames.CONTROL_TICK, Double.NaN);
        double window = snapshot.get(MetricNames.CONTROL_WINDOW_SEC, Double.NaN);
        double elapsed = snapshot.get(MetricNames.OBSERVATION_ELAPSED_SEC, Double.NaN);
        boolean regular = Double.isFinite(tick) && Double.isFinite(window) && Double.isFinite(elapsed) && window > 0
                && Math.abs(elapsed - window) <= Math.max(0.1, 0.25 * window);
        int[] capacities = space.current(actuators);
        if (!regular || capacities == null || !PolicyFeatures.isActive(snapshot)) {
            features.reset();
            previousTick = -1;
            return fallback(alternative, actuators, !regular ? "irregular_window" : capacities == null ? "missing_actuator" : "idle");
        }
        if ((long) tick != previousTick + 1) features.reset();
        previousTick = (long) tick;
        double[] state = features.observe(snapshot, capacities);
        if (features.count() < model.minHistory()) return fallback(alternative, actuators, "short_history");

        long started = clock.getAsLong();
        try {
            List<int[]> candidates = space.candidates(snapshot, actuators);
            int[] reference = space.admissible(alternative.desiredValues(), snapshot, actuators);
            PolicyModel.Choice choice = model.choose(features, state, features.bin(snapshot), capacities, candidates,
                    reference, new PolicyModel.Deadline(budgetNanos, clock));
            modelDecisions++;
            if ("baseline".equals(choice.reason())) {
                // The rules' own proposal, with their reasons, so the record says which rule fired.
                ControlAction.Builder kept = ControlAction.builder().also(alternative.desiredValues());
                for (Actuator actuator : actuators) {
                    String own = alternative.reason(actuator.name());
                    kept.reason(actuator.name(), "coordinated_baseline" + (own == null ? "" : ":" + own));
                }
                return kept.baseline(alternative.desiredValues()).build();
            }
            deviations++;
            int changed = 0;
            for (int r = 0; r < capacities.length; r++) if (choice.targets()[r] != capacities[r]) changed++;
            if (changed > 0) moves++;
            if (changed > 1) jointMoves++;
            ControlAction chosen = space.action(choice.targets(), "coordinated_" + choice.reason());
            return ControlAction.builder().also(chosen.desiredValues()).alsoReasons(chosen.reasons())
                    .baseline(alternative.desiredValues()).build();
        } catch (PolicyModel.Deadline.Exceeded e) {
            return fallback(alternative, actuators, "budget");
        } catch (RuntimeException e) {
            return fallback(alternative, actuators, "model_error");
        } finally {
            long spent = Math.max(0, clock.getAsLong() - started);
            totalNanos += spent;
            maxNanos = Math.max(maxNanos, spent);
        }
    }

    private ControlAction fallback(ControlAction action, List<Actuator> actuators, String reason) {
        fallbacks.merge(reason, 1L, Long::sum);
        ControlAction.Builder result = ControlAction.builder().also(action.desiredValues());
        for (Actuator actuator : actuators) {
            String own = action.reason(actuator.name());
            result.reason(actuator.name(), "coordinated_fallback_" + reason + (own == null ? "" : ":" + own));
        }
        return result.baseline(action.desiredValues()).build();
    }

    /** Counters for reports: how often the policy decided, moved, and why it deferred. */
    public synchronized Map<String, Object> statistics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisions", decisions);
        result.put("modelDecisions", modelDecisions);
        result.put("deviationsFromBaseline", deviations);
        result.put("modelMoves", moves);
        result.put("modelJointMoves", jointMoves);
        result.put("fallbacks", Map.copyOf(fallbacks));
        long scored = Math.max(1, decisions - fallbacks.entrySet().stream()
                .filter(e -> List.of("irregular_window", "missing_actuator", "idle", "short_history").contains(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum());
        result.put("meanInferenceMs", totalNanos / (double) scored / 1e6);
        result.put("maxInferenceMs", maxNanos / 1e6);
        return result;
    }

    public Map<String, Object> parameters() {
        return Map.of("modelSha256", model.sha256(), "modelVersion", 3, "featureVersion", PolicyFeatures.VERSION,
                "treeCount", model.treeCount(), "inferenceBudgetMs", budgetNanos / 1e6,
                "resources", model.resources().stream().map(r -> r.signals().actuatorName()).toList());
    }
}
