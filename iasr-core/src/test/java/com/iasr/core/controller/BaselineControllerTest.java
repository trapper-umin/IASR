package com.iasr.core.controller;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.support.TestActuator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineControllerTest {

    private static final double SLO = 200.0;
    private static final String CONCURRENCY = ConcurrencyLimiter.ACTUATOR_NAME;
    private static final String POOL = HikariPoolActuator.ACTUATOR_NAME;

    private static BaselineController controller() {
        return new BaselineController(
                SLO,
                0.7,    // comfortFactor -> comfortable below 140 ms
                5,      // concurrencyIncreaseStep
                0.85,   // concurrencyDecreaseFactor
                0.01,   // errorRateThreshold
                0.005,  // timeoutRateThreshold
                1,      // hikariStep
                0.0,    // hikariPendingThreshold
                10.0);  // hikariAcquireTimeThresholdMs
    }

    /** Snapshot with live traffic and the given latency / error / timeout signals. */
    private static MetricsSnapshot traffic(double p95, double errorRate, double timeoutRate) {
        return MetricsSnapshot.builder()
                .put(MetricNames.LATENCY_P95_MS, p95)
                .put(MetricNames.GOODPUT_RPS, 100.0)
                .put(MetricNames.ERROR_RATE, errorRate)
                .put(MetricNames.TIMEOUT_RATE, timeoutRate)
                .build();
    }

    private static MetricsSnapshot idle() {
        return MetricsSnapshot.builder()
                .put(MetricNames.LATENCY_P95_MS, 0.0)
                .put(MetricNames.GOODPUT_RPS, 0.0)
                .put(MetricNames.ERROR_RATE, 0.0)
                .put(MetricNames.TIMEOUT_RATE, 0.0)
                .build();
    }

    private static MetricsSnapshot pool(double p95, double pending, double acquireP95) {
        return MetricsSnapshot.builder()
                .put(MetricNames.LATENCY_P95_MS, p95)
                .put(MetricNames.GOODPUT_RPS, 100.0)
                .put(MetricNames.ERROR_RATE, 0.0)
                .put(MetricNames.TIMEOUT_RATE, 0.0)
                .put(MetricNames.HIKARI_PENDING, pending)
                .put(MetricNames.HIKARI_ACQUIRE_P95_MS, acquireP95)
                .build();
    }

    @Nested
    @DisplayName("concurrency (AIMD)")
    class Concurrency {

        @Test
        @DisplayName("additive increase when latency is comfortably below the SLO")
        void increasesWhenComfortable() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            ControlAction action = controller().decide(traffic(100, 0, 0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(105);
        }

        @Test
        @DisplayName("multiplicative decrease when the SLO is violated")
        void decreasesWhenSloViolated() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            ControlAction action = controller().decide(traffic(300, 0, 0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(85);
        }

        @Test
        @DisplayName("holds in the band between comfortable and violating")
        void holdsInTheMiddleBand() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            // 140 < 150 < 200: neither comfortable nor violating.
            ControlAction action = controller().decide(traffic(150, 0, 0), List.of(limiter));

            assertThat(action.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("decreases when the error rate crosses the threshold")
        void decreasesOnErrors() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            ControlAction action = controller().decide(traffic(100, 0.05, 0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(85);
        }

        @Test
        @DisplayName("decreases when the timeout rate crosses the threshold")
        void decreasesOnTimeouts() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            ControlAction action = controller().decide(traffic(100, 0, 0.05), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(85);
        }

        @Test
        @DisplayName("never proposes a limit below 1")
        void neverGoesBelowOne() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 1, 1, 500);

            ControlAction action = controller().decide(traffic(300, 0, 0), List.of(limiter));

            assertThat(action.isEmpty()).isTrue();
            assertThat(limiter.currentValue()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("idle handling")
    class Idle {

        @Test
        @DisplayName("holds while idle, then shrinks once idleness is sustained")
        void shrinksOnlyAfterSustainedIdle() {
            BaselineController controller = controller();
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            // The first 23 idle ticks must not touch the limit.
            for (int i = 0; i < 23; i++) {
                assertThat(controller.decide(idle(), List.of(limiter)).isEmpty())
                        .as("idle tick %d", i + 1)
                        .isTrue();
            }

            // The 24th consecutive idle tick starts reclaiming capacity.
            ControlAction action = controller.decide(idle(), List.of(limiter));
            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(95);
        }

        @Test
        @DisplayName("a single tick of real traffic resets the idle streak")
        void trafficResetsIdleStreak() {
            BaselineController controller = controller();
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            for (int i = 0; i < 23; i++) {
                controller.decide(idle(), List.of(limiter));
            }
            // Real traffic in the middle band: holds, and clears the streak.
            controller.decide(traffic(150, 0, 0), List.of(limiter));

            assertThat(controller.decide(idle(), List.of(limiter)).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("hikari pool")
    class Pool {

        @Test
        @DisplayName("grows the pool when threads queue for connections and acquire time is high")
        void growsOnConnectionDeficit() {
            TestActuator poolActuator = new TestActuator(POOL, 10, 1, 50);

            ControlAction action = controller().decide(pool(100, 3, 50), List.of(poolActuator));

            assertThat(action.getOrDefault(POOL, -1)).isEqualTo(11);
        }

        @Test
        @DisplayName("grows the pool even while the SLO is violated — the shortage is the cause")
        void growsDuringSloViolation() {
            TestActuator poolActuator = new TestActuator(POOL, 10, 1, 50);

            ControlAction action = controller().decide(pool(500, 3, 50), List.of(poolActuator));

            assertThat(action.getOrDefault(POOL, -1)).isEqualTo(11);
        }

        @Test
        @DisplayName("shrinks the pool after a sustained stretch without pending threads")
        void shrinksWhenNoDeficitForLong() {
            BaselineController controller = controller();
            TestActuator poolActuator = new TestActuator(POOL, 10, 1, 50);

            for (int i = 0; i < 9; i++) {
                assertThat(controller.decide(pool(100, 0, 0), List.of(poolActuator)).isEmpty())
                        .as("tick %d without pending", i + 1)
                        .isTrue();
            }

            ControlAction action = controller.decide(pool(100, 0, 0), List.of(poolActuator));

            assertThat(action.getOrDefault(POOL, -1)).isEqualTo(9);
        }

        @Test
        @DisplayName("rolls back an increase that made latency worse on the very next tick")
        void rollsBackHarmfulIncrease() {
            BaselineController controller = controller();
            TestActuator poolActuator = new TestActuator(POOL, 5, 1, 50);

            // Tick 1: connection deficit -> grow.  This path returns early, and the
            // controller must still remember the pool size it saw on this tick.
            ControlAction grow = controller.decide(pool(100, 3, 50), List.of(poolActuator));
            assertThat(grow.getOrDefault(POOL, -1)).isEqualTo(6);
            poolActuator.setSilently(6);

            // Tick 2: the deficit is gone but latency degraded sharply right after
            // the increase -> roll it back.
            ControlAction rollback = controller.decide(pool(300, 0, 0), List.of(poolActuator));

            assertThat(rollback.getOrDefault(POOL, -1)).isEqualTo(5);
        }

        @Test
        @DisplayName("does not mistake an idle-to-load latency spike for a harmful increase")
        void doesNotRollBackWithoutAnIncrease() {
            BaselineController controller = controller();
            TestActuator poolActuator = new TestActuator(POOL, 10, 1, 50);

            controller.decide(pool(100, 0, 0), List.of(poolActuator));
            // Latency jumps, but the pool was never increased.
            ControlAction action = controller.decide(pool(300, 0, 0), List.of(poolActuator));

            assertThat(action.isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("ignores actuators it does not know about")
    void ignoresUnknownActuators() {
        List<Actuator> actuators = List.of(new TestActuator("cache_size", 100, 1, 500));

        assertThat(controller().decide(traffic(100, 0, 0), actuators).isEmpty()).isTrue();
    }
}
