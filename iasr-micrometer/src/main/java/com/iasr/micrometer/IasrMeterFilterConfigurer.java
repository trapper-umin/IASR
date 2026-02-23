package com.iasr.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

import java.util.Set;

/**
 * Registers a {@link MeterFilter} that enables client-side percentile
 * computation for the meters that IASR relies on.
 * <p>
 * Without this filter, {@code Timer.takeSnapshot().percentileValues()} returns
 * an empty array and all latency/acquire-time percentiles in the dataset will
 * be zero.
 * <p>
 * The IASR Spring Boot starter calls {@link #apply(MeterRegistry)}
 * automatically.  When using IASR without Spring, call it once before
 * creating {@link MicrometerMetricsProvider}.
 */
public final class IasrMeterFilterConfigurer {

    private static final Set<String> PERCENTILE_METERS = Set.of(
            "http.server.requests",
            "jvm.gc.pause",
            "hikaricp.connections.acquire"
    );

    private static final double[] PERCENTILES = {0.5, 0.75, 0.95, 0.99};

    private IasrMeterFilterConfigurer() { }

    /**
     * Creates the IASR meter filter.  Can be registered as a Spring bean or
     * passed to {@link MeterRegistry#config()}.
     */
    public static MeterFilter createFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    DistributionStatisticConfig config) {
                if (PERCENTILE_METERS.contains(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentiles(PERCENTILES)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    /**
     * Convenience method: creates and applies the filter to the given registry.
     */
    public static void apply(MeterRegistry registry) {
        registry.config().meterFilter(createFilter());
    }
}
