package com.iasr.core.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetRecordTest {

    private static final Instant TS = Instant.parse("2026-02-14T11:20:05.123Z");

    private static DatasetRecord record(Map<String, Double> state, Map<String, Integer> action) {
        return new DatasetRecord(TS, 5.0, state, action);
    }

    @Test
    @DisplayName("serializes the documented schema, with a null outcome until t+1")
    void serializesWithoutOutcome() {
        DatasetRecord r = record(Map.of("latency_p95_ms", 42.5), Map.of("concurrency_limit", 140));

        String json = r.toJsonLine();

        assertThat(json).isEqualTo("{\"schemaVersion\":3,\"sequence\":0,\"timestamp\":\"2026-02-14T11:20:05.123Z\","
                + "\"stateWindowStartedAt\":null,\"outcomeTimestamp\":null,\"windowSec\":5,"
                + "\"resourcesBefore\":{},\"cooldownRemaining\":{},"
                + "\"state\":{\"latency_p95_ms\":42.5},"
                + "\"action\":{\"concurrency_limit\":140},"
                + "\"intent\":{},\"reasons\":{},"
                + "\"outcome\":null}");
    }

    @Test
    @DisplayName("includes the outcome once it is attached")
    void serializesWithOutcome() {
        DatasetRecord r = record(Map.of("latency_p95_ms", 42.5), Map.of("concurrency_limit", 140));
        r.setOutcome(Map.of("latency_p95_ms", 47.9));

        assertThat(r.hasOutcome()).isTrue();
        assertThat(r.toJsonLine()).contains("\"outcome\":{\"latency_p95_ms\":47.9}");
    }

    @Test
    @DisplayName("writes whole numbers without a trailing .0")
    void integralValuesAreCompact() {
        DatasetRecord r = record(Map.of("inflight", 120.0), Map.of());

        assertThat(r.toJsonLine()).contains("\"inflight\":120");
    }

    @Test
    @DisplayName("maps NaN and infinity to JSON null instead of emitting invalid JSON")
    void nonFiniteValuesBecomeNull() {
        Map<String, Double> state = new LinkedHashMap<>();
        state.put("nan", Double.NaN);
        state.put("pos_inf", Double.POSITIVE_INFINITY);
        state.put("neg_inf", Double.NEGATIVE_INFINITY);

        String json = record(state, Map.of()).toJsonLine();

        assertThat(json).contains("\"nan\":null", "\"pos_inf\":null", "\"neg_inf\":null");
        assertThat(json).doesNotContain("NaN", "Infinity");
    }

    @Test
    @DisplayName("preserves metric insertion order so dataset columns stay stable")
    void preservesInsertionOrder() {
        Map<String, Double> state = new LinkedHashMap<>();
        state.put("b", 2.0);
        state.put("a", 1.0);
        state.put("c", 3.0);

        assertThat(record(state, Map.of()).toJsonLine())
                .contains("\"state\":{\"b\":2,\"a\":1,\"c\":3}");
    }

    @Test
    @DisplayName("renders empty state and action as empty objects")
    void handlesEmptyMaps() {
        assertThat(record(Map.of(), Map.of()).toJsonLine())
                .contains("\"state\":{}")
                .contains("\"action\":{}");
    }

    @Test
    @DisplayName("records what the controller wanted alongside what was applied")
    void recordsIntentAndReasons() {
        DatasetRecord r = new DatasetRecord(TS, 5.0,
                Map.of("latency_p95_ms", 300.0),
                Map.of("concurrency_limit", 120),
                Map.of("concurrency_limit", 140),
                Map.of("concurrency_limit", "decrease_slo_gradient|clamped_max_step"));

        String json = r.toJsonLine();

        assertThat(json)
                .as("the applied value alone would teach a learner the guardrail, not the policy")
                .contains("\"action\":{\"concurrency_limit\":120}")
                .contains("\"intent\":{\"concurrency_limit\":140}")
                .contains("\"reasons\":{\"concurrency_limit\":\"decrease_slo_gradient|clamped_max_step\"}");
    }

    @Test
    @DisplayName("copies the maps it is given, so later mutation cannot corrupt the record")
    void defensivelyCopiesInputs() {
        Map<String, Double> state = new LinkedHashMap<>();
        state.put("latency_p95_ms", 10.0);
        DatasetRecord r = record(state, Map.of());

        state.put("injected", 99.0);

        assertThat(r.toJsonLine()).doesNotContain("injected");
    }
}
