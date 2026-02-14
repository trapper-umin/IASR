package com.iasr.core.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of aggregated metrics collected over one control window Δt.
 * <p>
 * The snapshot is a named bag of {@code double} values.  The core engine does
 * not prescribe fixed metric names — adapters publish whatever they can collect
 * and the controller reads the keys it needs.  Standard keys are listed in
 * {@link MetricNames}.
 */
public final class MetricsSnapshot {

    private final Map<String, Double> values;

    private MetricsSnapshot(Map<String, Double> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Returns the value for the given metric key, or {@code defaultValue} if absent. */
    public double get(String key, double defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    /** Returns the value for the given metric key, or {@code Double.NaN} if absent. */
    public double get(String key) {
        return values.getOrDefault(key, Double.NaN);
    }

    /** Returns {@code true} if the snapshot contains the given metric key. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Returns an unmodifiable view of all metric values. */
    public Map<String, Double> asMap() {
        return values;
    }

    @Override
    public String toString() {
        return "MetricsSnapshot" + values;
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Double> map = new LinkedHashMap<>();

        private Builder() {}

        public Builder put(String key, double value) {
            map.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, Double> entries) {
            map.putAll(entries);
            return this;
        }

        public MetricsSnapshot build() {
            return new MetricsSnapshot(map);
        }
    }
}
