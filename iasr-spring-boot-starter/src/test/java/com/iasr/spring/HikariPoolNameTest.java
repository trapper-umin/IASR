package com.iasr.spring;

import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.micrometer.MicrometerMetricsProvider;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code hikaricp.*} meter is tagged with the pool's name.  If the reader
 * looks under the wrong name it finds nothing and reports zeros — the pool
 * controller then sees no pending threads, never acts, and the dataset gains a
 * column of zeros, all without a single error anywhere.  These tests pin the
 * name down end to end.
 */
class HikariPoolNameTest {

    private static HikariDataSource poolNamed(String name) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(name);
        ds.setMaximumPoolSize(10);
        return ds;
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IasrAutoConfiguration.class))
            .withPropertyValues("iasr.window-ms=600000");

    @Test
    @DisplayName("reads Hikari metrics published under a non-default pool name")
    void readsCustomPoolName() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("dataSource", HikariDataSource.class, () -> poolNamed("orders-pool"))
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Gauge.builder("hikaricp.connections.pending", () -> 7.0)
                            .tag("pool", "orders-pool").register(registry);
                    Gauge.builder("hikaricp.connections.active", () -> 4.0)
                            .tag("pool", "orders-pool").register(registry);

                    MetricsSnapshot snapshot =
                            context.getBean(MicrometerMetricsProvider.class).snapshot();

                    assertThat(snapshot.get(MetricNames.HIKARI_PENDING))
                            .as("a pool named anything but HikariPool-1 must still be found")
                            .isEqualTo(7.0);
                    assertThat(snapshot.get(MetricNames.HIKARI_ACTIVE)).isEqualTo(4.0);
                });
    }

    @Test
    @DisplayName("does not read another pool's metrics")
    void doesNotCrossReadPools() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("dataSource", HikariDataSource.class, () -> poolNamed("orders-pool"))
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Gauge.builder("hikaricp.connections.pending", () -> 9.0)
                            .tag("pool", "reporting-pool").register(registry);

                    MetricsSnapshot snapshot =
                            context.getBean(MicrometerMetricsProvider.class).snapshot();

                    assertThat(snapshot.get(MetricNames.HIKARI_PENDING)).isZero();
                });
    }

    @Test
    @DisplayName("publishes the pool name holder only when a Hikari pool exists")
    void holderOnlyWithHikari() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).doesNotHaveBean(HikariPoolName.class));

        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("dataSource", HikariDataSource.class, () -> poolNamed("orders-pool"))
                .run(context -> {
                    assertThat(context).hasSingleBean(HikariPoolName.class);
                    assertThat(context.getBean(HikariPoolName.class).resolver().get())
                            .isEqualTo("orders-pool");
                });
    }
}
