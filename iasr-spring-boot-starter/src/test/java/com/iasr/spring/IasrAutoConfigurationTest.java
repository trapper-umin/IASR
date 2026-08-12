package com.iasr.spring;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.engine.ControlEngine;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IasrAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            // Keep the loop from ticking repeatedly during the test.
            .withPropertyValues("iasr.window-ms=600000");

    private static HikariDataSource unstartedHikari() {
        HikariDataSource ds = new HikariDataSource();
        ds.setMaximumPoolSize(10);
        return ds;
    }

    private static Optional<ActuatorGuardrail> guardrailFor(ControlEngine engine, String actuator) {
        return engine.getConfig().guardrails().stream()
                .filter(g -> g.actuatorName().equals(actuator))
                .findFirst();
    }

    @Nested
    @DisplayName("enablement")
    class Enablement {

        @Test
        @DisplayName("wires the engine in active mode by default")
        void activeByDefault() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(ControlEngine.class);
                assertThat(context).hasSingleBean(ConcurrencyLimiter.class);

                ControlEngine engine = context.getBean(ControlEngine.class);
                assertThat(engine.getConfig().applyActions()).isTrue();
                assertThat(engine.isRunning()).isTrue();
            });
        }

        @Test
        @DisplayName("iasr.enabled=false backs off completely — no beans at all")
        void masterSwitchDisablesEverything() {
            runner.withPropertyValues("iasr.enabled=false").run(context -> {
                assertThat(context).doesNotHaveBean(ControlEngine.class);
                assertThat(context).doesNotHaveBean(ConcurrencyLimiter.class);
                assertThat(context).doesNotHaveBean(IasrProperties.class);
            });
        }

        @Test
        @DisplayName("iasr.apply-actions=false still creates and starts the engine, in observe-only mode")
        void observeOnlyModeIsReachable() {
            runner.withPropertyValues("iasr.apply-actions=false").run(context -> {
                assertThat(context).hasSingleBean(ControlEngine.class);

                ControlEngine engine = context.getBean(ControlEngine.class);
                assertThat(engine.getConfig().applyActions())
                        .as("observe-only: metrics and dataset only, no actuator changes")
                        .isFalse();
                assertThat(engine.isRunning())
                        .as("the loop must still run — that is the point of observe-only")
                        .isTrue();
                assertThat(engine.getConfig().datasetLoggingEnabled())
                        .as("the dataset is what observe-only mode exists to collect")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("observe-only mode is independent of dataset logging")
        void datasetLoggingCanBeTurnedOffSeparately() {
            runner.withPropertyValues("iasr.apply-actions=false", "iasr.dataset-logging-enabled=false")
                    .run(context -> {
                        ControlEngine engine = context.getBean(ControlEngine.class);
                        assertThat(engine.getConfig().applyActions()).isFalse();
                        assertThat(engine.getConfig().datasetLoggingEnabled()).isFalse();
                    });
        }

        @Test
        @DisplayName("backs off when there is no MeterRegistry")
        void requiresMeterRegistry() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
                    .run(context -> assertThat(context).doesNotHaveBean(ControlEngine.class));
        }
    }

    @Nested
    @DisplayName("properties")
    class Properties {

        @Test
        @DisplayName("binds the documented configuration keys")
        void bindsProperties() {
            runner.withPropertyValues(
                    "iasr.slo-latency-ms=350",
                    "iasr.concurrency.initial-limit=42",
                    "iasr.concurrency.min-limit=7",
                    "iasr.concurrency.max-limit=99",
                    "iasr.controller.comfort-factor=0.5"
            ).run(context -> {
                IasrProperties props = context.getBean(IasrProperties.class);
                assertThat(props.getSloLatencyMs()).isEqualTo(350.0);
                assertThat(props.getConcurrency().getInitialLimit()).isEqualTo(42);
                assertThat(props.getController().getComfortFactor()).isEqualTo(0.5);

                ConcurrencyLimiter limiter = context.getBean(ConcurrencyLimiter.class);
                assertThat(limiter.currentValue()).isEqualTo(42);
                assertThat(limiter.minValue()).isEqualTo(7);
                assertThat(limiter.maxValue()).isEqualTo(99);
            });
        }

        @Test
        @DisplayName("feeds concurrency bounds into the guardrail")
        void concurrencyGuardrailMatchesProperties() {
            runner.withPropertyValues(
                    "iasr.concurrency.min-limit=7",
                    "iasr.concurrency.max-limit=99",
                    "iasr.concurrency.max-step=3",
                    "iasr.concurrency.cooldown-ticks=4"
            ).run(context -> {
                ControlEngine engine = context.getBean(ControlEngine.class);
                ActuatorGuardrail rule = guardrailFor(engine, ConcurrencyLimiter.ACTUATOR_NAME).orElseThrow();

                assertThat(rule.minValue()).isEqualTo(7);
                assertThat(rule.maxValue()).isEqualTo(99);
                assertThat(rule.maxStep()).isEqualTo(3);
                assertThat(rule.cooldownTicks()).isEqualTo(4);
            });
        }
    }

    @Nested
    @DisplayName("SLO-violation policy")
    class SloPolicy {

        @Test
        @DisplayName("concurrency increases are blocked during a violation")
        void concurrencyBlocksIncrease() {
            runner.run(context -> {
                ControlEngine engine = context.getBean(ControlEngine.class);
                assertThat(guardrailFor(engine, ConcurrencyLimiter.ACTUATOR_NAME).orElseThrow()
                        .blockIncreaseOnSloViolation()).isTrue();
            });
        }

        @Test
        @DisplayName("pool increases are permitted during a violation — a shortage is a likely cause")
        void hikariPermitsIncrease() {
            runner.withBean("dataSource", HikariDataSource.class,
                    IasrAutoConfigurationTest::unstartedHikari).run(context -> {
                ControlEngine engine = context.getBean(ControlEngine.class);

                assertThat(guardrailFor(engine, HikariPoolActuator.ACTUATOR_NAME).orElseThrow()
                        .blockIncreaseOnSloViolation())
                        .as("blocking this would trap the loop in the state it is escaping")
                        .isFalse();
            });
        }

        @Test
        @DisplayName("the pool policy can be tightened back up by configuration")
        void hikariPolicyIsConfigurable() {
            runner.withBean("dataSource", HikariDataSource.class, IasrAutoConfigurationTest::unstartedHikari)
                    .withPropertyValues("iasr.hikari.block-increase-on-slo-violation=true")
                    .run(context -> {
                        ControlEngine engine = context.getBean(ControlEngine.class);
                        assertThat(guardrailFor(engine, HikariPoolActuator.ACTUATOR_NAME).orElseThrow()
                                .blockIncreaseOnSloViolation()).isTrue();
                    });
        }
    }

    @Nested
    @DisplayName("actuator discovery")
    class Discovery {

        @Test
        @DisplayName("registers only the concurrency limiter when there is no DataSource")
        void concurrencyOnlyWithoutDataSource() {
            runner.run(context -> {
                assertThat(context).doesNotHaveBean(HikariPoolActuator.class);
                ControlEngine engine = context.getBean(ControlEngine.class);
                assertThat(engine.getActuators()).hasSize(1);
                assertThat(engine.getActuators().get(0).name())
                        .isEqualTo(ConcurrencyLimiter.ACTUATOR_NAME);
            });
        }

        @Test
        @DisplayName("picks up the Hikari actuator when a HikariDataSource is present")
        void discoversHikariActuator() {
            runner.withBean("dataSource", HikariDataSource.class,
                    IasrAutoConfigurationTest::unstartedHikari).run(context -> {
                assertThat(context).hasSingleBean(HikariPoolActuator.class);
                ControlEngine engine = context.getBean(ControlEngine.class);

                assertThat(engine.getActuators())
                        .extracting(Actuator::name)
                        .containsExactlyInAnyOrder(
                                ConcurrencyLimiter.ACTUATOR_NAME, HikariPoolActuator.ACTUATOR_NAME);
            });
        }

        @Test
        @DisplayName("adopts arbitrary custom Actuator beans without extra configuration")
        void discoversCustomActuators() {
            runner.withBean("cacheActuator", Actuator.class, () -> new Actuator() {
                @Override public String name() { return "cache_size"; }
                @Override public int currentValue() { return 100; }
                @Override public void apply(int newValue) { }
                @Override public int minValue() { return 1; }
                @Override public int maxValue() { return 1000; }
            }).run(context -> {
                ControlEngine engine = context.getBean(ControlEngine.class);
                assertThat(engine.getActuators())
                        .extracting(Actuator::name)
                        .contains("cache_size");
            });
        }

        @Test
        @DisplayName("does not register the concurrency limiter twice")
        void limiterIsNotDuplicated() {
            runner.run(context -> {
                ControlEngine engine = context.getBean(ControlEngine.class);
                assertThat(engine.getActuators())
                        .filteredOn(a -> a.name().equals(ConcurrencyLimiter.ACTUATOR_NAME))
                        .hasSize(1);
            });
        }
    }
}
