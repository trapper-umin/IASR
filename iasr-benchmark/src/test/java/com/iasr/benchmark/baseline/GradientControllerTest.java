package com.iasr.benchmark.baseline;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradientControllerTest {

    private static final String NAME = ConcurrencyLimiter.ACTUATOR_NAME;

    /** Minimal actuator that reports whatever value the test sets. */
    private static final class Limiter implements Actuator {
        private int value;
        Limiter(int value) { this.value = value; }
        @Override public String name() { return NAME; }
        @Override public int currentValue() { return value; }
        @Override public void apply(int newValue) { this.value = newValue; }
        @Override public int minValue() { return 1; }
        @Override public int maxValue() { return 500; }
    }

    private static MetricsSnapshot at(double latencyMs, double inflight) {
        return MetricsSnapshot.builder()
                .put(MetricNames.LATENCY_P95_MS, latencyMs)
                .put(MetricNames.INFLIGHT, inflight)
                .put(MetricNames.QUEUE_LENGTH, 0.0)
                .build();
    }

    @Test
    @DisplayName("shrinks the limit as latency rises above the best observed")
    void shrinksWhenLatencyDegrades() {
        GradientController controller = new GradientController(1, 500);
        Limiter limiter = new Limiter(100);

        // Establish the no-load reference at 50 ms.
        controller.decide(at(50, 90), List.of(limiter));
        // Latency quadruples: the gradient floor allows at most a halving,
        // smoothed, so the limit must fall but not collapse.
        ControlAction action = controller.decide(at(200, 90), List.of(limiter));

        int newLimit = action.getOrDefault(NAME, 100);
        assertThat(newLimit).isLessThan(100).isGreaterThan(50);
        assertThat(action.reason(NAME)).isEqualTo("gradient_shrink");
    }

    @Test
    @DisplayName("labels by the direction taken, not by the gradient value")
    void labelsByActualDirection() {
        GradientController controller = new GradientController(1, 500);
        Limiter limiter = new Limiter(100);
        controller.decide(at(50, 95), List.of(limiter));

        // Gradient just below one, but the allowance term still raises the
        // limit — calling that a shrink would misreport the decision.
        ControlAction action = controller.decide(at(52, 95), List.of(limiter));

        int newLimit = action.getOrDefault(NAME, 100);
        if (newLimit > 100) {
            assertThat(action.reason(NAME)).isEqualTo("gradient_grow");
        } else if (newLimit < 100) {
            assertThat(action.reason(NAME)).isEqualTo("gradient_shrink");
        }
    }

    @Test
    @DisplayName("does not move a limit nothing is pressing against")
    void holdsWhenUnderutilised() {
        GradientController controller = new GradientController(1, 500);
        Limiter limiter = new Limiter(100);
        controller.decide(at(50, 90), List.of(limiter));

        ControlAction action = controller.decide(at(200, 5), List.of(limiter));

        assertThat(action.isEmpty()).isTrue();
        assertThat(action.reason(NAME)).isEqualTo("gradient_underutilised");
    }

    @Test
    @DisplayName("grows the limit while latency stays at the best observed")
    void growsWhenLatencyIsAtItsBest() {
        GradientController controller = new GradientController(1, 500);
        Limiter limiter = new Limiter(100);
        controller.decide(at(50, 95), List.of(limiter));

        ControlAction action = controller.decide(at(50, 95), List.of(limiter));

        assertThat(action.getOrDefault(NAME, 100))
                .as("gradient of 1 leaves the allowance term, which is what lets it probe upward")
                .isGreaterThan(100);
        assertThat(action.reason(NAME)).isEqualTo("gradient_grow");
    }

    @Test
    @DisplayName("holds when the window produced no latency samples")
    void holdsWithoutSamples() {
        GradientController controller = new GradientController(1, 500);

        ControlAction action = controller.decide(at(0, 0), List.of(new Limiter(100)));

        assertThat(action.reason(NAME)).isEqualTo("gradient_no_samples");
    }

    @Test
    @DisplayName("respects the configured bounds")
    void respectsBounds() {
        GradientController controller = new GradientController(80, 120);
        Limiter limiter = new Limiter(100);
        controller.decide(at(50, 95), List.of(limiter));

        for (int i = 0; i < 40; i++) {
            ControlAction a = controller.decide(at(5000, 95), List.of(limiter));
            a.desiredValues().forEach((n, v) -> limiter.apply(v));
        }

        assertThat(limiter.currentValue()).isGreaterThanOrEqualTo(80);
    }
}
