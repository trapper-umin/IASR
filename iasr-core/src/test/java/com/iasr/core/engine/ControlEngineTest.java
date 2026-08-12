package com.iasr.core.engine;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.config.ControlEngineConfig;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsProvider;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.support.TestActuator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlEngineTest {

    private static final String NAME = "concurrency_limit";

    /** Metrics provider that counts down a latch on every tick. */
    private static class CountingProvider implements MetricsProvider {
        final CountDownLatch ticks;
        final AtomicInteger calls = new AtomicInteger();

        CountingProvider(int expectedTicks) {
            this.ticks = new CountDownLatch(expectedTicks);
        }

        @Override
        public MetricsSnapshot snapshot() {
            calls.incrementAndGet();
            ticks.countDown();
            return MetricsSnapshot.builder()
                    .put(MetricNames.LATENCY_P95_MS, 50.0)
                    .put(MetricNames.GOODPUT_RPS, 100.0)
                    .build();
        }
    }

    /** Controller that always asks for a fixed value. */
    private static Controller alwaysWants(int value) {
        return (snapshot, actuators) -> ControlAction.builder().set(NAME, value).build();
    }

    private static ControlEngineConfig.Builder config(MetricsProvider provider) {
        return ControlEngineConfig.builder()
                .windowMs(30)
                .sloLatencyMs(200)
                .datasetLoggingEnabled(false)
                .metricsProvider(provider);
    }

    private static void runFor(ControlEngine engine, CountingProvider provider) throws Exception {
        engine.start();
        try {
            assertThat(provider.ticks.await(10, TimeUnit.SECONDS))
                    .as("control loop should have ticked")
                    .isTrue();
        } finally {
            engine.stop();
        }
    }

    @Test
    @DisplayName("applies controller decisions when running in active mode")
    void activeModeAppliesActions() throws Exception {
        CountingProvider provider = new CountingProvider(3);
        TestActuator actuator = new TestActuator(NAME, 100, 1, 500);

        ControlEngine engine = new ControlEngine(config(provider)
                .applyActions(true)
                .controller(alwaysWants(120))
                .addActuator(actuator)
                .addGuardrail(new ActuatorGuardrail(NAME, 1, 500, 50, 0))
                .build());

        runFor(engine, provider);

        assertThat(actuator.appliedValues()).isNotEmpty();
        assertThat(actuator.currentValue()).isEqualTo(120);
    }

    @Test
    @DisplayName("observe-only mode keeps collecting metrics but never touches an actuator")
    void observeOnlyModeDoesNotApplyActions() throws Exception {
        CountingProvider provider = new CountingProvider(3);
        TestActuator actuator = new TestActuator(NAME, 100, 1, 500);

        ControlEngine engine = new ControlEngine(config(provider)
                .applyActions(false)
                .controller(alwaysWants(120))
                .addActuator(actuator)
                .addGuardrail(new ActuatorGuardrail(NAME, 1, 500, 50, 0))
                .build());

        runFor(engine, provider);

        assertThat(provider.calls.get())
                .as("metrics are still collected in observe-only mode")
                .isGreaterThanOrEqualTo(3);
        assertThat(actuator.appliedValues()).isEmpty();
        assertThat(actuator.currentValue()).isEqualTo(100);
    }

    @Test
    @DisplayName("survives a metrics provider that throws, and keeps ticking")
    void tickFailureDoesNotKillTheLoop() throws Exception {
        CountDownLatch ticks = new CountDownLatch(3);
        AtomicInteger calls = new AtomicInteger();
        MetricsProvider flaky = () -> {
            ticks.countDown();
            if (calls.incrementAndGet() % 2 == 0) {
                throw new IllegalStateException("boom");
            }
            return MetricsSnapshot.builder().put(MetricNames.LATENCY_P95_MS, 10.0).build();
        };

        ControlEngine engine = new ControlEngine(config(flaky).build());
        engine.start();
        try {
            assertThat(ticks.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            engine.stop();
        }

        assertThat(calls.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("skips the tick when the provider returns no snapshot")
    void nullSnapshotSkipsTheTick() throws Exception {
        CountDownLatch ticks = new CountDownLatch(2);
        TestActuator actuator = new TestActuator(NAME, 100, 1, 500);
        MetricsProvider nullProvider = () -> {
            ticks.countDown();
            return null;
        };

        ControlEngine engine = new ControlEngine(config(nullProvider)
                .controller(alwaysWants(120))
                .addActuator(actuator)
                .build());
        engine.start();
        try {
            assertThat(ticks.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            engine.stop();
        }

        assertThat(actuator.appliedValues()).isEmpty();
    }

    @Test
    @DisplayName("start is idempotent and stop reports the loop as no longer running")
    void lifecycleIsIdempotent() throws Exception {
        CountingProvider provider = new CountingProvider(1);
        ControlEngine engine = new ControlEngine(config(provider).build());

        assertThat(engine.isRunning()).isFalse();
        engine.start();
        engine.start();
        assertThat(engine.isRunning()).isTrue();
        assertThat(provider.ticks.await(10, TimeUnit.SECONDS)).isTrue();

        engine.stop();
        engine.stop();
        assertThat(engine.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a failing actuator does not stop the other actuators from being applied")
    void actuatorFailureIsIsolated() throws Exception {
        CountingProvider provider = new CountingProvider(3);
        TestActuator healthy = new TestActuator("healthy", 100, 1, 500);
        Actuator broken = new Actuator() {
            @Override public String name() { return "broken"; }
            @Override public int currentValue() { return 1; }
            @Override public void apply(int newValue) { throw new IllegalStateException("nope"); }
            @Override public int minValue() { return 1; }
            @Override public int maxValue() { return 10; }
        };

        Controller controller = (snapshot, actuators) -> ControlAction.builder()
                .set("broken", 5)
                .set("healthy", 120)
                .build();

        ControlEngine engine = new ControlEngine(config(provider)
                .controller(controller)
                .addActuator(broken)
                .addActuator(healthy)
                .build());

        runFor(engine, provider);

        assertThat(healthy.currentValue()).isEqualTo(120);
    }

    @Test
    @DisplayName("config requires a metrics provider and defaults the controller")
    void configValidation() {
        assertThatThrownBy(() -> ControlEngineConfig.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metricsProvider");

        ControlEngineConfig cfg = ControlEngineConfig.builder()
                .metricsProvider(MetricsSnapshot.builder()::build)
                .build();

        assertThat(cfg.controller()).isNotNull();
        assertThat(cfg.applyActions()).isTrue();
        assertThat(cfg.windowSec()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("the deprecated enabled() flag still drives observe-only mode")
    @SuppressWarnings("deprecation")
    void deprecatedEnabledAliasStillWorks() {
        ControlEngineConfig cfg = ControlEngineConfig.builder()
                .metricsProvider(MetricsSnapshot.builder()::build)
                .enabled(false)
                .build();

        assertThat(cfg.applyActions()).isFalse();
        assertThat(cfg.enabled()).isFalse();
    }

    @Test
    @DisplayName("actuator and guardrail lists are immutable snapshots of the builder")
    void configListsAreImmutable() {
        ControlEngineConfig cfg = ControlEngineConfig.builder()
                .metricsProvider(MetricsSnapshot.builder()::build)
                .addActuator(new TestActuator(NAME, 1, 1, 2))
                .build();

        List<Actuator> actuators = cfg.actuators();
        assertThatThrownBy(() -> actuators.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
