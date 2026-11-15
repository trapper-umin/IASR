package com.iasr.core.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable container for the set of parameter changes decided by a
 * {@link Controller} on a single tick.
 * <p>
 * Keys are actuator names (matching {@link com.iasr.core.actuator.Actuator#name()}),
 * values are the <b>desired</b> new values (before guardrail clamping).
 *
 * <h3>Reasons</h3>
 * Alongside the values, an action may carry a short reason per actuator —
 * including for actuators it decided <em>not</em> to change.  Without it a tick
 * that leaves the limit alone is indistinguishable from one where the
 * controller wanted to act and something stopped it, and those are different
 * states of the world: metrics in the neutral band, a cooldown still running,
 * an SLO block, a demand gate that saw no queue.  The controller already
 * computes the distinction; recording it turns the dataset into a decision log
 * and makes "why did it not react?" answerable without attaching a debugger to
 * production.
 * <p>
 * Reasons are optional and carry no semantics for the engine — they are
 * observability, not control flow. {@link #isEmpty()} still reflects desired
 * changes alone.
 */
public final class ControlAction {

    /** Singleton empty action — "do nothing". */
    public static final ControlAction NONE =
            new ControlAction(Collections.emptyMap(), Collections.emptyMap(), null);

    private final Map<String, Integer> desired;
    private final Map<String, String> reasons;
    private final Map<String, Integer> baseline;

    private ControlAction(Map<String, Integer> desired, Map<String, String> reasons, Map<String, Integer> baseline) {
        this.desired = Collections.unmodifiableMap(new LinkedHashMap<>(desired));
        this.reasons = Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
        this.baseline = baseline == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(baseline));
    }

    /**
     * What a baseline controller proposed in the same tick, when the decision
     * came from a controller that decides relative to one (a learned policy
     * deferring to rules). Empty when the deciding controller has no baseline;
     * present but empty when the baseline proposed to keep every value.
     */
    public Optional<Map<String, Integer>> baselineValues() {
        return Optional.ofNullable(baseline);
    }

    public Map<String, Integer> desiredValues() {
        return desired;
    }

    /** Reason per actuator, for every actuator the controller considered. */
    public Map<String, String> reasons() {
        return reasons;
    }

    public String reason(String actuatorName) {
        return reasons.get(actuatorName);
    }

    public int getOrDefault(String actuatorName, int fallback) {
        return desired.getOrDefault(actuatorName, fallback);
    }

    public boolean isEmpty() {
        return desired.isEmpty();
    }

    @Override
    public String toString() {
        String text = reasons.isEmpty() ? "ControlAction" + desired : "ControlAction" + desired + " " + reasons;
        return baseline == null ? text : text + " baseline=" + baseline;
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Integer> map = new LinkedHashMap<>();
        private final Map<String, String> why = new LinkedHashMap<>();
        private Map<String, Integer> baseline;

        private Builder() {}

        /** Record the baseline controller's proposal for this tick (see {@link ControlAction#baselineValues()}). */
        public Builder baseline(Map<String, Integer> proposal) {
            baseline = new LinkedHashMap<>(proposal);
            return this;
        }

        public Builder set(String actuatorName, int desiredValue) {
            map.put(actuatorName, desiredValue);
            return this;
        }

        /** Record a desired change together with why it was chosen. */
        public Builder set(String actuatorName, int desiredValue, String reason) {
            map.put(actuatorName, desiredValue);
            return reason(actuatorName, reason);
        }

        /**
         * Record why an actuator was left alone.  Deliberately separate from
         * {@link #set}: a hold is a decision, but it is not a change, and the
         * engine must keep treating it as "nothing to apply".
         */
        public Builder hold(String actuatorName, String reason) {
            return reason(actuatorName, reason);
        }

        public Builder reason(String actuatorName, String reason) {
            if (reason != null) {
                why.put(actuatorName, reason);
            }
            return this;
        }

        /** Copy in desired values from another action. */
        public Builder also(Map<String, Integer> values) {
            map.putAll(values);
            return this;
        }

        /** Copy in reasons from another action. */
        public Builder alsoReasons(Map<String, String> otherReasons) {
            why.putAll(otherReasons);
            return this;
        }

        public ControlAction build() {
            if (map.isEmpty() && why.isEmpty() && baseline == null) return NONE;
            return new ControlAction(map, why, baseline);
        }
    }
}
