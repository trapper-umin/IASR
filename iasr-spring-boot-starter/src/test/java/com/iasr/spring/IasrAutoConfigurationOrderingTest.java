package com.iasr.spring;

import com.iasr.core.engine.ControlEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @ConditionalOnBean} on an auto-configuration class is evaluated against
 * whatever bean definitions exist at that point, so it is only correct if this
 * auto-configuration is ordered after whatever defines the {@link MeterRegistry}.
 * <p>
 * The other tests in this module supply the registry as a <em>user</em> bean,
 * which is always registered before any auto-configuration and therefore always
 * matches — proving nothing about ordering.  These tests use Spring Boot's real
 * metrics auto-configurations instead, which is how a production application
 * gets its registry.
 */
class IasrAutoConfigurationOrderingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("iasr.window-ms=600000");

    @Test
    @DisplayName("wires up when the registry comes from Spring Boot's own metrics auto-configuration")
    void matchesRegistryFromAutoConfiguration() {
        runner.withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        IasrAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(MeterRegistry.class);
                    assertThat(context)
                            .as("IASR must be ordered after the metrics auto-configurations, "
                                    + "otherwise @ConditionalOnBean sees no registry and backs off")
                            .hasSingleBean(ControlEngine.class);
                });
    }

    @Test
    @DisplayName("backs off cleanly when nothing defines a MeterRegistry")
    void backsOffWithoutAnyRegistry() {
        runner.withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ControlEngine.class);
                    assertThat(context).doesNotHaveBean(ConcurrencyLimitFilter.class);
                });
    }

    @Test
    @DisplayName("the meter filter that enables percentile histograms reaches the registry")
    void percentileFilterIsAppliedToTheRegistry() {
        runner.withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        IasrAutoConfiguration.class))
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    io.micrometer.core.instrument.Timer timer =
                            io.micrometer.core.instrument.Timer.builder("jvm.gc.pause")
                                    .register(registry);
                    for (int i = 0; i < 20; i++) {
                        timer.record(java.time.Duration.ofMillis(10));
                    }

                    assertThat(timer.takeSnapshot().percentileValues())
                            .as("without the filter reaching the registry, every p95 in the "
                                    + "dataset silently degrades to a max-based over-estimate")
                            .isNotEmpty();
                });
    }
}
