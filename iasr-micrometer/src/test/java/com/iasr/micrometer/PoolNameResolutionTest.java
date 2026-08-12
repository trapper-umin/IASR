package com.iasr.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HikariCP assigns a generated pool name only when the pool starts, so a name
 * read during context startup is frequently still {@code null}.  Caching that
 * null as "HikariPool-1" forever would leave the provider reading a pool that
 * may not exist, silently reporting zeros for every Hikari metric.
 */
class PoolNameResolutionTest {

    @Test
    @DisplayName("falls back to the conventional name until the pool has a real one")
    void fallsBackBeforeResolution() {
        MicrometerMetricsProvider provider = MicrometerMetricsProvider.withPoolNameSupplier(
                new SimpleMeterRegistry(), () -> null, null);

        assertThat(provider.hikariPoolName())
                .isEqualTo(MicrometerMetricsProvider.DEFAULT_POOL_NAME);
    }

    @Test
    @DisplayName("picks up the real name once the pool starts, then caches it")
    void resolvesLazilyThenCaches() {
        String[] lateName = {null};
        MicrometerMetricsProvider provider = MicrometerMetricsProvider.withPoolNameSupplier(
                new SimpleMeterRegistry(), () -> lateName[0], null);

        assertThat(provider.hikariPoolName())
                .isEqualTo(MicrometerMetricsProvider.DEFAULT_POOL_NAME);

        lateName[0] = "late-pool";
        assertThat(provider.hikariPoolName())
                .as("the real name must be picked up once the pool starts")
                .isEqualTo("late-pool");

        lateName[0] = null;
        assertThat(provider.hikariPoolName())
                .as("a resolved name is cached and must not regress to the fallback")
                .isEqualTo("late-pool");
    }

    @Test
    @DisplayName("treats a blank name as unresolved")
    void blankNameIsNotAccepted() {
        MicrometerMetricsProvider provider = MicrometerMetricsProvider.withPoolNameSupplier(
                new SimpleMeterRegistry(), () -> "   ", null);

        assertThat(provider.hikariPoolName())
                .isEqualTo(MicrometerMetricsProvider.DEFAULT_POOL_NAME);
    }

    @Test
    @DisplayName("an explicit name is used as given")
    void explicitNameIsUsed() {
        MicrometerMetricsProvider provider =
                new MicrometerMetricsProvider(new SimpleMeterRegistry(), "orders-pool");

        assertThat(provider.hikariPoolName()).isEqualTo("orders-pool");
    }
}
