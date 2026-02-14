package com.iasr.core.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable container for the set of parameter changes decided by a
 * {@link Controller} on a single tick.
 * <p>
 * Keys are actuator names (matching {@link com.iasr.core.actuator.Actuator#name()}),
 * values are the <b>desired</b> new values (before guardrail clamping).
 */
public final class ControlAction {

    /** Singleton empty action — "do nothing". */
    public static final ControlAction NONE = new ControlAction(Collections.emptyMap());

    private final Map<String, Integer> desired;

    private ControlAction(Map<String, Integer> desired) {
        this.desired = Collections.unmodifiableMap(new LinkedHashMap<>(desired));
    }

    public Map<String, Integer> desiredValues() {
        return desired;
    }

    public int getOrDefault(String actuatorName, int fallback) {
        return desired.getOrDefault(actuatorName, fallback);
    }

    public boolean isEmpty() {
        return desired.isEmpty();
    }

    @Override
    public String toString() {
        return "ControlAction" + desired;
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Integer> map = new LinkedHashMap<>();

        private Builder() {}

        public Builder set(String actuatorName, int desiredValue) {
            map.put(actuatorName, desiredValue);
            return this;
        }

        public ControlAction build() {
            if (map.isEmpty()) return NONE;
            return new ControlAction(map);
        }
    }
}
