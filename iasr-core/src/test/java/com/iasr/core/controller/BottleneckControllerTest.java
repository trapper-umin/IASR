package com.iasr.core.controller;

import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.support.TestActuator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of the decomposition is telling apart two situations that a single
 * latency number renders identical: throttling and saturation. Most of these
 * tests are that distinction from different angles.
 */
class BottleneckControllerTest {

    private static final double SLO = 200.0;
    private static final String LIMIT = ConcurrencyLimiter.ACTUATOR_NAME;
    private static final String POOL = HikariPoolActuator.ACTUATOR_NAME;

    private static MetricsSnapshot where(double admission, double poolWait,
                                         double dependency, double p95) {
        return MetricsSnapshot.builder()
                .put(MetricNames.GOODPUT_RPS, 100.0)
                .put(MetricNames.LATENCY_P95_MS, p95)
                .put(MetricNames.ADMISSION_WAIT_MEAN_MS, admission)
                .put(MetricNames.HIKARI_ACQUIRE_MEAN_MS, poolWait)
                .put(MetricNames.DEPENDENCY_MEAN_MS, dependency)
                .build();
    }

    private static List<com.iasr.core.actuator.Actuator> actuators(TestActuator... a) {
        return List.of(a);
    }

    @Test
    @DisplayName("admits more when the queue for permits is what the time is going on")
    void raisesLimitWhenAdmissionDominates() {
        BottleneckController controller = new BottleneckController(SLO);
        TestActuator limiter = new TestActuator(LIMIT, 50, 1, 500);
        TestActuator pool = new TestActuator(POOL, 10, 1, 50);

        // Establish the dependency's baseline at 50 ms.
        controller.decide(where(0, 0, 50, 60), actuators(limiter, pool));

        // Now most of the time is spent queued for a permit while the
        // dependency is unchanged: the limiter is the constraint, and a rule
        // reading total latency alone would shed and make it worse.
        ControlAction action = controller.decide(where(200, 2, 50, 260), actuators(limiter, pool));

        assertThat(action.getOrDefault(LIMIT, 50))
                .as("the queue is the delay, so admitting more shortens it")
                .isGreaterThan(50);
        assertThat(action.reason(LIMIT)).isEqualTo("bottleneck_admission");
    }

    @Test
    @DisplayName("sheds when the dependency itself is the slow part")
    void shedsWhenDependencyDominates() {
        BottleneckController controller = new BottleneckController(SLO);
        TestActuator limiter = new TestActuator(LIMIT, 100, 1, 500);
        TestActuator pool = new TestActuator(POOL, 10, 1, 50);

        controller.decide(where(0, 0, 50, 60), actuators(limiter, pool));
        ControlAction action = controller.decide(where(5, 2, 400, 500), actuators(limiter, pool));

        assertThat(action.getOrDefault(LIMIT, 100)).isLessThan(100);
        assertThat(action.reason(LIMIT)).isEqualTo("bottleneck_dependency_shed");
        assertThat(action.desiredValues()).doesNotContainKey(POOL);
    }

    @Test
    @DisplayName("grows the pool when connections are the constraint")
    void growsPoolWhenPoolDominates() {
        BottleneckController controller = new BottleneckController(SLO);
        TestActuator limiter = new TestActuator(LIMIT, 100, 1, 500);
        TestActuator pool = new TestActuator(POOL, 10, 1, 50);

        controller.decide(where(0, 0, 50, 60), actuators(limiter, pool));
        ControlAction action = controller.decide(where(3, 300, 50, 360), actuators(limiter, pool));

        assertThat(action.getOrDefault(POOL, 10)).isEqualTo(11);
        assertThat(action.reason(POOL)).isEqualTo("bottleneck_pool");
        assertThat(action.desiredValues())
                .as("only the bottleneck moves; the limit is not the problem here")
                .doesNotContainKey(LIMIT);
    }

    @Test
    @DisplayName("does nothing when no single term dominates")
    void holdsWithoutADominantTerm() {
        BottleneckController controller = new BottleneckController(SLO);
        TestActuator limiter = new TestActuator(LIMIT, 100, 1, 500);
        TestActuator pool = new TestActuator(POOL, 10, 1, 50);

        controller.decide(where(0, 0, 50, 60), actuators(limiter, pool));
        // Three comparable excesses: 20 ms queued, 20 ms for a connection,
        // 20 ms of extra dependency time.  No single one is the bottleneck.
        ControlAction action = controller.decide(where(20, 20, 70, 130), actuators(limiter, pool));

        assertThat(action.isEmpty()).isTrue();
        assertThat(action.reason(LIMIT)).isEqualTo("no_dominant_bottleneck");
    }

    @Test
    @DisplayName("ignores avoidable delay too small to be worth acting on")
    void ignoresNoise() {
        BottleneckController controller = new BottleneckController(SLO);
        TestActuator limiter = new TestActuator(LIMIT, 100, 1, 500);
        TestActuator pool = new TestActuator(POOL, 10, 1, 50);

        controller.decide(where(0, 0, 50, 60), actuators(limiter, pool));
        ControlAction action = controller.decide(where(1, 0, 50, 60), actuators(limiter, pool));

        assertThat(action.isEmpty()).isTrue();
        assertThat(action.reason(LIMIT)).isEqualTo("no_avoidable_delay");
    }

    @Test
    @DisplayName("does not shed while the target is still being met")
    void doesNotShedWithinSlo() {
        BottleneckController controller = new BottleneckController(SLO);
        TestActuator limiter = new TestActuator(LIMIT, 100, 1, 500);
        TestActuator pool = new TestActuator(POOL, 10, 1, 50);

        controller.decide(where(0, 0, 50, 60), actuators(limiter, pool));
        ControlAction action = controller.decide(where(2, 2, 120, 150), actuators(limiter, pool));

        assertThat(action.isEmpty()).isTrue();
        assertThat(action.reason(LIMIT)).isEqualTo("bottleneck_dependency_within_slo");
    }

    @Test
    @DisplayName("an unreachable target does not collapse the limit")
    void unreachableSloDoesNotCollapseTheLimit() {
        // Target below what the dependency can deliver even unloaded.
        BottleneckController controller = new BottleneckController(40.0);
        TestActuator limiter = new TestActuator(LIMIT, 100, 1, 500);
        TestActuator pool = new TestActuator(POOL, 10, 1, 50);

        controller.decide(where(0, 0, 50, 55), actuators(limiter, pool));
        for (int i = 0; i < 30; i++) {
            ControlAction a = controller.decide(where(1, 1, 52, 58), actuators(limiter, pool));
            a.desiredValues().forEach((n, v) -> {
                if (n.equals(LIMIT)) limiter.apply(v);
            });
        }

        assertThat(limiter.currentValue())
                .as("an SLO the dependency cannot meet must not drive the limit to the floor")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("holds everything while there is no traffic to reason about")
    void holdsWhenIdle() {
        BottleneckController controller = new BottleneckController(SLO);
        TestActuator limiter = new TestActuator(LIMIT, 100, 1, 500);

        ControlAction action = controller.decide(
                MetricsSnapshot.builder().put(MetricNames.GOODPUT_RPS, 0.0).build(),
                actuators(limiter));

        assertThat(action.isEmpty()).isTrue();
        assertThat(action.reason(LIMIT)).isEqualTo("idle");
    }
}
