package com.iasr.core.guardrail;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.support.TestActuator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailsTest {

    private static final double SLO = 200.0;
    private static final String NAME = "concurrency_limit";

    private static MetricsSnapshot latency(double p95) {
        return MetricsSnapshot.builder().put(MetricNames.LATENCY_P95_MS, p95).build();
    }

    private static MetricsSnapshot healthy() {
        return latency(50.0);
    }

    private static MetricsSnapshot violating() {
        return latency(500.0);
    }

    private static ControlAction want(int value) {
        return ControlAction.builder().set(NAME, value).build();
    }

    @Nested
    @DisplayName("cooldown")
    class Cooldown {

        @ParameterizedTest(name = "after {0} quiet tick(s)")
        @ValueSource(ints = {0, 1, 2, 3, 7})
        @DisplayName("allows the very first change immediately, whatever the loop did beforehand")
        void firstChangeIsNotDelayedByStartupTicks(int quietTicks) {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            List<Actuator> actuators = List.of(actuator);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 3)), SLO);

            // Ticks where the controller asks for nothing must not accumulate into
            // an artificial cooldown window.  The counts below deliberately include
            // values under cooldownTicks, which is where a naive counter would
            // wrongly report "changed recently" and block the first real change.
            for (int i = 0; i < quietTicks; i++) {
                assertThat(guardrails.enforce(ControlAction.NONE, actuators, healthy()).isEmpty()).isTrue();
            }

            ControlAction result = guardrails.enforce(want(110), actuators, healthy());

            assertThat(result.getOrDefault(NAME, -1))
                    .as("no change has ever been applied, so nothing should be on cooldown")
                    .isEqualTo(110);
        }

        @Test
        @DisplayName("a long quiet stretch does not overflow the tick counter")
        void quietCounterSaturates() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            List<Actuator> actuators = List.of(actuator);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 3)), SLO);

            for (int i = 0; i < 1_000; i++) {
                guardrails.enforce(ControlAction.NONE, actuators, healthy());
            }

            assertThat(guardrails.enforce(want(110), actuators, healthy()).getOrDefault(NAME, -1))
                    .isEqualTo(110);
        }

        @Test
        @DisplayName("suppresses changes for exactly cooldownTicks after a change is applied")
        void suppressesUntilCooldownElapses() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            List<Actuator> actuators = List.of(actuator);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 3)), SLO);

            ControlAction first = guardrails.enforce(want(110), actuators, healthy());
            assertThat(first.getOrDefault(NAME, -1)).isEqualTo(110);
            actuator.apply(110);

            // cooldownTicks=3 means three ticks must pass before the next change.
            for (int i = 0; i < 3; i++) {
                assertThat(guardrails.enforce(want(120), actuators, healthy()).isEmpty())
                        .as("tick %d after the change is still within cooldown", i + 1)
                        .isTrue();
            }

            assertThat(guardrails.enforce(want(120), actuators, healthy()).getOrDefault(NAME, -1))
                    .isEqualTo(120);
        }

        @Test
        @DisplayName("suppressed changes do not consume cooldown")
        void suppressionDoesNotAdvanceCooldownIncorrectly() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            List<Actuator> actuators = List.of(actuator);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 2)), SLO);

            guardrails.enforce(want(110), actuators, healthy());
            actuator.apply(110);

            // Two suppressed ticks for cooldownTicks=2, then the change goes through.
            // Repeated suppression must not shorten or extend the window.
            assertThat(guardrails.enforce(want(120), actuators, healthy()).isEmpty()).isTrue();
            assertThat(guardrails.enforce(want(120), actuators, healthy()).isEmpty()).isTrue();
            assertThat(guardrails.enforce(want(120), actuators, healthy()).getOrDefault(NAME, -1))
                    .isEqualTo(120);
        }
    }

    @Nested
    @DisplayName("SLO violation")
    class SloViolation {

        @Test
        @DisplayName("blocks increases when the actuator opts in (the default)")
        void blocksIncreaseByDefault() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 0)), SLO);

            ControlAction result = guardrails.enforce(want(150), List.of(actuator), violating());

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("permits increases for actuators exempted from the block")
        void permitsIncreaseWhenExempt() {
            // The connection pool is the motivating case: threads queued for a
            // connection show up as latency, so the shortage is the cause of the
            // violation and growing the pool is the fix.
            TestActuator pool = new TestActuator("hikari_max_pool", 10, 1, 50);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail("hikari_max_pool", 1, 50, 2, 0, false)), SLO);

            ControlAction result = guardrails.enforce(
                    ControlAction.builder().set("hikari_max_pool", 11).build(),
                    List.of(pool), violating());

            assertThat(result.getOrDefault("hikari_max_pool", -1)).isEqualTo(11);
        }

        @Test
        @DisplayName("never blocks decreases")
        void allowsDecreaseDuringViolation() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 0)), SLO);

            ControlAction result = guardrails.enforce(want(80), List.of(actuator), violating());

            assertThat(result.getOrDefault(NAME, -1)).isEqualTo(80);
        }

        @Test
        @DisplayName("does not block when the snapshot carries no latency reading")
        void noLatencyMetricMeansNoViolation() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 0)), SLO);

            ControlAction result = guardrails.enforce(
                    want(150), List.of(actuator), MetricsSnapshot.builder().build());

            assertThat(result.getOrDefault(NAME, -1)).isEqualTo(150);
        }
    }

    @Nested
    @DisplayName("clamping")
    class Clamping {

        @Test
        @DisplayName("limits the per-tick step in both directions")
        void clampsStep() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 20, 0)), SLO);

            assertThat(guardrails.enforce(want(400), List.of(actuator), healthy())
                    .getOrDefault(NAME, -1)).isEqualTo(120);

            actuator.setSilently(100);
            assertThat(guardrails.enforce(want(10), List.of(actuator), healthy())
                    .getOrDefault(NAME, -1)).isEqualTo(80);
        }

        @Test
        @DisplayName("allows a larger reduction than increase when configured asymmetrically")
        void allowsFasterShrinkThanGrowth() {
            TestActuator actuator = new TestActuator(NAME, 200, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 20, 0, true, 100)), SLO);

            // Growing is rate-limited: +20 of the requested +200.
            assertThat(guardrails.enforce(want(400), List.of(actuator), healthy())
                    .getOrDefault(NAME, -1)).isEqualTo(220);

            // Shedding is not throttled to the same rate: -100 of the requested -190.
            actuator.setSilently(200);
            assertThat(guardrails.enforce(want(10), List.of(actuator), healthy())
                    .getOrDefault(NAME, -1))
                    .as("the guardrail must not throttle the emergency brake like the accelerator")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("falls back to a symmetric step when no down-step is configured")
        void symmetricByDefault() {
            TestActuator actuator = new TestActuator(NAME, 200, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 20, 0)), SLO);

            assertThat(guardrails.enforce(want(10), List.of(actuator), healthy())
                    .getOrDefault(NAME, -1)).isEqualTo(180);
        }

        @Test
        @DisplayName("clamps the result into [min, max]")
        void clampsAbsoluteBounds() {
            TestActuator actuator = new TestActuator(NAME, 15, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 10, 500, 50, 0)), SLO);

            assertThat(guardrails.enforce(want(1), List.of(actuator), healthy())
                    .getOrDefault(NAME, -1)).isEqualTo(10);
        }

        @Test
        @DisplayName("emits nothing when clamping lands back on the current value")
        void noActionWhenClampedToCurrent() {
            TestActuator actuator = new TestActuator(NAME, 10, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 10, 500, 50, 0)), SLO);

            assertThat(guardrails.enforce(want(5), List.of(actuator), healthy()).isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("passes through actuators that have no guardrail configured")
    void unguardedActuatorPassesThrough() {
        TestActuator actuator = new TestActuator("custom", 100, 1, 500);
        Guardrails guardrails = new Guardrails(List.of(), SLO);

        ControlAction result = guardrails.enforce(
                ControlAction.builder().set("custom", 999).build(), List.of(actuator), violating());

        assertThat(result.getOrDefault("custom", -1)).isEqualTo(999);
    }

    @Test
    @DisplayName("ignores actions naming actuators that are not registered")
    void unknownActuatorNameIsIgnored() {
        TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
        Guardrails guardrails = new Guardrails(
                List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 0)), SLO);

        ControlAction result = guardrails.enforce(
                ControlAction.builder().set("does_not_exist", 1).build(),
                List.of(actuator), healthy());

        assertThat(result.isEmpty()).isTrue();
    }

    @Nested
    @DisplayName("decision reasons")
    class Reasons {

        private static ControlAction wantWithReason(int value, String reason) {
            return ControlAction.builder().set(NAME, value, reason).build();
        }

        @Test
        @DisplayName("joins what the controller decided with what this layer did to it")
        void annotatesTheOutcome() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 20, 0)), SLO);

            ControlAction result = guardrails.enforce(
                    wantWithReason(400, "increase_headroom"), List.of(actuator), healthy());

            assertThat(result.reason(NAME)).isEqualTo("increase_headroom|clamped_max_step");
        }

        @Test
        @DisplayName("records suppression, so a blocked change is not mistaken for no decision")
        void recordsSuppression() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 0)), SLO);

            ControlAction result = guardrails.enforce(
                    wantWithReason(150, "increase_headroom"), List.of(actuator), violating());

            assertThat(result.isEmpty()).as("nothing is applied").isTrue();
            assertThat(result.reason(NAME))
                    .as("but the intent and the block are both on record")
                    .isEqualTo("increase_headroom|suppressed_slo_block");
        }

        @Test
        @DisplayName("carries a hold reason through even when nothing was requested")
        void carriesHoldReasons() {
            TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
            Guardrails guardrails = new Guardrails(
                    List.of(new ActuatorGuardrail(NAME, 1, 500, 50, 0)), SLO);

            ControlAction result = guardrails.enforce(
                    ControlAction.builder().hold(NAME, "hold_limit_not_binding").build(),
                    List.of(actuator), healthy());

            assertThat(result.reason(NAME)).isEqualTo("hold_limit_not_binding");
        }
    }

    @Test
    @DisplayName("rejects invalid guardrail configuration")
    void validatesConstructorArguments() {
        assertThatThrownBy(() -> new ActuatorGuardrail("", 1, 10, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActuatorGuardrail(NAME, -1, 10, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActuatorGuardrail(NAME, 10, 5, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActuatorGuardrail(NAME, 1, 10, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActuatorGuardrail(NAME, 1, 10, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("defaults to blocking increases during SLO violation")
    void defaultsToBlocking() {
        assertThat(new ActuatorGuardrail(NAME, 1, 10, 1, 0).blockIncreaseOnSloViolation()).isTrue();
    }
}
