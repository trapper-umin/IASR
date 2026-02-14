package com.iasr.core.dataset;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of the JSONL dataset produced on every control-loop tick.
 * <p>
 * Schema:
 * <pre>{@code
 * {
 *   "timestamp":  "2026-02-14T11:20:05.123Z",
 *   "windowSec":  5,
 *   "state":      { ... aggregated metrics ... },
 *   "action":     { "concurrency_limit": 140, "hikari_max_pool": 28 },
 *   "outcome":    { ... metrics of the NEXT window, filled on t+1 ... }
 * }
 * }</pre>
 *
 * <p>{@code outcome} is {@code null} until the next tick populates it.
 * The record is logged only after outcome is available (i.e. at tick t+1
 * we log the record from tick t).  The very last record of a session may
 * have {@code outcome = null}.
 */
public final class DatasetRecord {

    private final Instant timestamp;
    private final double windowSec;
    private final Map<String, Double> state;
    private final Map<String, Integer> action;
    private Map<String, Double> outcome; // mutable — filled at t+1

    public DatasetRecord(Instant timestamp,
                         double windowSec,
                         Map<String, Double> state,
                         Map<String, Integer> action) {
        this.timestamp = timestamp;
        this.windowSec = windowSec;
        this.state = new LinkedHashMap<>(state);
        this.action = new LinkedHashMap<>(action);
    }

    public Instant getTimestamp() { return timestamp; }
    public double getWindowSec() { return windowSec; }
    public Map<String, Double> getState() { return state; }
    public Map<String, Integer> getAction() { return action; }
    public Map<String, Double> getOutcome() { return outcome; }

    public boolean hasOutcome() { return outcome != null; }

    /**
     * Attach outcome metrics (from the next control window).
     * Called exactly once, at tick t+1.
     */
    public void setOutcome(Map<String, Double> outcome) {
        this.outcome = outcome != null ? new LinkedHashMap<>(outcome) : null;
    }

    /**
     * Serialize to a single-line JSON string.
     * <p>
     * Intentionally hand-written to keep the core module free of Jackson.
     * The format is stable and documented — see README for schema.
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendString(sb, "timestamp", timestamp.toString());
        sb.append(',');
        appendNumber(sb, "windowSec", windowSec);
        sb.append(',');
        appendDoubleMap(sb, "state", state);
        sb.append(',');
        appendIntMap(sb, "action", action);
        sb.append(',');
        if (outcome != null) {
            appendDoubleMap(sb, "outcome", outcome);
        } else {
            sb.append("\"outcome\":null");
        }
        sb.append('}');
        return sb.toString();
    }

    // ── JSON helpers (no external deps) ──────────────────────────────────

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(value).append('"');
    }

    private static void appendNumber(StringBuilder sb, String key, double value) {
        sb.append('"').append(key).append("\":");
        if (value == (long) value) {
            sb.append((long) value);
        } else {
            sb.append(value);
        }
    }

    private static void appendDoubleMap(StringBuilder sb, String key, Map<String, Double> map) {
        sb.append('"').append(key).append("\":{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            double v = entry.getValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                sb.append("null");
            } else if (v == (long) v) {
                sb.append((long) v);
            } else {
                sb.append(v);
            }
        }
        sb.append('}');
    }

    private static void appendIntMap(StringBuilder sb, String key, Map<String, Integer> map) {
        sb.append('"').append(key).append("\":{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        sb.append('}');
    }
}
