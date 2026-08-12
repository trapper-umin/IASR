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

    /**
     * Controller with the demand gate and the Little's-law target switched off,
     * so the AIMD rules can be exercised in isolation.
     */
    private static BaselineController controller() {
        return controller(0.0, 0.0);
    }

    private static BaselineController controller(double utilizationGate, double sloTargetHeadroom) {
        return controller(utilizationGate, sloTargetHeadroom, 0.0, 0.0);
    }

    private static BaselineController controller(double utilizationGate, double sloTargetHeadroom,
                                                 double minGradient, double idleShrinkThreshold) {
        return new BaselineController(
                SLO,
                0.7,    // comfortFactor -> comfortable below 140 ms
                5,      // concurrencyIncreaseStep
                0.85,   // concurrencyDecreaseFactor
                0.01,   // errorRateThreshold
                0.005,  // timeoutRateThreshold
                1,      // hikariStep
                0.0,    // hikariPendingThreshold
                10.0,   // hikariAcquireTimeThresholdMs
                utilizationGate,
                sloTargetHeadroom,
                minGradient,
                idleShrinkThreshold);
    }

    /**
     * Snapshot with live traffic.  In-flight is reported at the actuator's full
     * value so the demand gate, when enabled, sees a saturated limit.
     */
    private static MetricsSnapshot traffic(double p95, double errorRate, double timeoutRate) {
        return traffic(p95, errorRate, timeoutRate, 100.0, 1000.0);
    }

    private static MetricsSnapshot traffic(double p95, double errorRate, double timeoutRate,
                                           double goodputRps, double inflight) {
        return MetricsSnapshot.builder()
                .put(MetricNames.LATENCY_P95_MS, p95)
                .put(MetricNames.GOODPUT_RPS, goodputRps)
                .put(MetricNames.ERROR_RATE, errorRate)
                .put(MetricNames.TIMEOUT_RATE, timeoutRate)
                .put(MetricNames.INFLIGHT, inflight)
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
    @DisplayName("demand gate")
    class DemandGate {

        @Test
        @DisplayName("does not grow the limit while most of it sits unused")
        void doesNotGrowWithoutDemand() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            // Healthy and fast, but only 7 of 100 permits are in use: the limit
            // is not what is holding anything back, so raising it buys nothing
            // and leaves less admission control for the next surge.
            ControlAction action = controller(0.9, 0.0)
                    .decide(traffic(50, 0, 0, 100.0, 7.0), List.of(limiter));

            assertThat(action.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("grows the limit once it is the binding constraint")
        void growsWhenSaturated() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            ControlAction action = controller(0.9, 0.0)
                    .decide(traffic(50, 0, 0, 100.0, 95.0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(105);
        }

        @Test
        @DisplayName("a healthy service does not ratchet its limit to the ceiling")
        void noDriftOverASustainedHealthyRun() {
            BaselineController controller = controller(0.9, 0.0);
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            // Sixty ticks of comfortable traffic at a steady 7 concurrent requests.
            for (int i = 0; i < 60; i++) {
                ControlAction action = controller.decide(
                        traffic(50, 0, 0, 100.0, 7.0), List.of(limiter));
                action.desiredValues().forEach((name, value) -> limiter.apply(value));
            }

            assertThat(limiter.currentValue())
                    .as("the limit must still protect the service when the surge arrives")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("the gate can be switched off to restore the old behaviour")
        void gateIsOptional() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            ControlAction action = controller(0.0, 0.0)
                    .decide(traffic(50, 0, 0, 100.0, 7.0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(105);
        }
    }

    @Nested
    @DisplayName("gradient decrease")
    class GradientDecrease {

        @Test
        @DisplayName("scales the cut to how far latency is past the target")
        void cutIsProportionalToTheError() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 200, 1, 500);

            // Latency 250 ms against a 200 ms SLO: gradient 0.8 -> 160.
            ControlAction mild = controller(0.0, 0.0, 0.5, 0.0)
                    .decide(traffic(250, 0, 0), List.of(limiter));
            assertThat(mild.getOrDefault(CONCURRENCY, -1)).isEqualTo(160);

            // Latency 400 ms: gradient 0.5 -> 100.  Twice the overshoot, twice the cut.
            TestActuator other = new TestActuator(CONCURRENCY, 200, 1, 500);
            ControlAction severe = controller(0.0, 0.0, 0.5, 0.0)
                    .decide(traffic(400, 0, 0), List.of(other));
            assertThat(severe.getOrDefault(CONCURRENCY, -1)).isEqualTo(100);
        }

        @Test
        @DisplayName("never removes more than the floor allows in one tick")
        void floorBoundsASingleTick() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 200, 1, 500);

            // Latency 20x the SLO would give gradient 0.05; the floor holds it at 0.5.
            ControlAction action = controller(0.0, 0.0, 0.5, 0.0)
                    .decide(traffic(4000, 0, 0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(100);
        }

        @Test
        @DisplayName("converges far faster than geometric decay")
        void convergesFasterThanGeometric() {
            TestActuator gradient = new TestActuator(CONCURRENCY, 200, 1, 500);
            TestActuator geometric = new TestActuator(CONCURRENCY, 200, 1, 500);
            BaselineController withGradient = controller(0.0, 0.0, 0.5, 0.0);
            BaselineController withGeometric = controller(0.0, 0.0);

            for (int i = 0; i < 5; i++) {
                withGradient.decide(traffic(600, 0, 0), List.of(gradient))
                        .desiredValues().forEach((n, v) -> gradient.apply(v));
                withGeometric.decide(traffic(600, 0, 0), List.of(geometric))
                        .desiredValues().forEach((n, v) -> geometric.apply(v));
            }

            assertThat(gradient.currentValue())
                    .as("five ticks of a threefold overshoot should have made real progress")
                    .isLessThan(geometric.currentValue() / 2);
        }

        @Test
        @DisplayName("falls back to the fixed factor for error-driven cuts")
        void errorsUseTheFixedFactor() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            // Latency is fine, so there is no ratio to scale by — errors alone.
            ControlAction action = controller(0.0, 0.0, 0.5, 0.0)
                    .decide(traffic(50, 0.5, 0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(85);
        }
    }

    @Nested
    @DisplayName("queue length as the demand signal")
    class QueueSignal {

        private static MetricsSnapshot withQueue(double inflight, double queue) {
            return MetricsSnapshot.builder()
                    .put(MetricNames.LATENCY_P95_MS, 50.0)
                    .put(MetricNames.GOODPUT_RPS, 100.0)
                    .put(MetricNames.ERROR_RATE, 0.0)
                    .put(MetricNames.TIMEOUT_RATE, 0.0)
                    .put(MetricNames.INFLIGHT, inflight)
                    .put(MetricNames.QUEUE_LENGTH, queue)
                    .build();
        }

        @Test
        @DisplayName("grows the limit when threads are waiting, whatever utilisation says")
        void queueProvesTheLimitIsBinding() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            // Utilisation is only 50%, so the heuristic alone would hold — but
            // threads are demonstrably blocked on the limit right now.
            ControlAction action = controller(0.9, 0.0)
                    .decide(withQueue(50.0, 12.0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(105);
        }

        @Test
        @DisplayName("an empty queue still lets utilisation decide")
        void fallsBackToUtilisation() {
            TestActuator saturated = new TestActuator(CONCURRENCY, 100, 1, 500);
            assertThat(controller(0.9, 0.0).decide(withQueue(95.0, 0.0), List.of(saturated))
                    .getOrDefault(CONCURRENCY, -1)).isEqualTo(105);

            TestActuator idle = new TestActuator(CONCURRENCY, 100, 1, 500);
            assertThat(controller(0.9, 0.0).decide(withQueue(7.0, 0.0), List.of(idle))
                    .isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Little's law target")
    class SloTarget {

        @Test
        @DisplayName("descends straight to the target instead of decaying towards it")
        void decreaseJumpsToTarget() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 190, 1, 500);

            // Serving 240 rps against a 200 ms SLO: L = 240 x 0.2 = 48.
            // Geometric decay alone would only reach 161 on this tick.
            ControlAction action = controller(0.0, 1.0)
                    .decide(traffic(500, 0, 0, 240.0, 190.0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(48);
        }

        @Test
        @DisplayName("keeps the geometric step when it is already the more aggressive one")
        void keepsGeometricWhenItIsLower() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 20, 1, 500);

            // Target is 48, geometric gives 17 — take the smaller.
            ControlAction action = controller(0.0, 1.0)
                    .decide(traffic(500, 0, 0, 240.0, 20.0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(17);
        }

        @Test
        @DisplayName("does not grow the limit beyond what the SLO needs")
        void increaseStopsAtTarget() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 46, 1, 500);

            // Saturated and comfortable, so AIMD wants +5 -> 51, but Little's
            // law says 48 is all the SLO requires at this throughput.
            ControlAction action = controller(0.9, 1.0)
                    .decide(traffic(50, 0, 0, 240.0, 46.0), List.of(limiter));

            assertThat(action.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("is ignored when throughput is too low to estimate from")
        void noTargetWithoutTraffic() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 100, 1, 500);

            // Errors present, so this is not the idle path, but goodput is far
            // too low for the estimate to mean anything.
            ControlAction action = controller(0.0, 1.0)
                    .decide(traffic(500, 0.5, 0, 0.5, 100.0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1))
                    .as("falls back to plain geometric decrease")
                    .isEqualTo(85);
        }

        @Test
        @DisplayName("headroom scales the target")
        void headroomScalesTarget() {
            TestActuator limiter = new TestActuator(CONCURRENCY, 190, 1, 500);

            // Half the headroom, half the target: 0.5 x 240 x 0.2 = 24.
            ControlAction action = controller(0.0, 0.5)
                    .decide(traffic(500, 0, 0, 240.0, 190.0), List.of(limiter));

            assertThat(action.getOrDefault(CONCURRENCY, -1)).isEqualTo(24);
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
