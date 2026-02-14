package com.iasr.core.metrics;

/**
 * Abstraction over the telemetry source.
 * <p>
 * Implementations collect raw data from Micrometer, JMX, custom counters,
 * etc. and produce an aggregated {@link MetricsSnapshot} that covers the
 * most recent control window Δt.
 * <p>
 * The core module has <b>no compile-time dependency</b> on any specific
 * metrics library — that coupling lives in adapter modules.
 */
@FunctionalInterface
public interface MetricsProvider {

    /**
     * Collect and return aggregated metrics for the current control window.
     * <p>
     * The engine calls this method once per tick.  Implementations are free
     * to cache / pre-aggregate between calls.
     *
     * @return a non-null snapshot; may contain zero entries if data is unavailable
     */
    MetricsSnapshot snapshot();
}
