package com.iasr.micrometer;

import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsProvider;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.metrics.WindowedLatencyTracker;
import com.iasr.core.metrics.WindowedLatencyTracker.WindowStats;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * {@link MetricsProvider} backed by Micrometer's {@link MeterRegistry} and
 * an optional {@link WindowedLatencyTracker} for per-window latency percentiles.
 * <p>
 * <b>Per-window latency tracking</b>: Micrometer's built-in percentiles are
 * computed over a decay window (~2 min), causing consecutive 5-second snapshots
 * to be nearly identical (state ≈ outcome in the dataset).  When a
 * {@code WindowedLatencyTracker} is provided, this provider reads p95/p99/mean
 * from the tracker instead, which accumulates request durations within each
 * IASR control window and resets on drain.
 *
 * <h3>Expected meters</h3>
 * <ul>
 *   <li>{@code http.server.requests} — Timer (throughput, error/timeout counts)</li>
 *   <li>{@code iasr.concurrency.inflight} — Gauge</li>
 *   <li>{@code iasr.concurrency.timeouts} — Counter</li>
 *   <li>{@code iasr.http.errors} — Counter</li>
 *   <li>{@code hikaricp.connections.active} — Gauge</li>
 *   <li>{@code hikaricp.connections.idle} — Gauge</li>
 *   <li>{@code hikaricp.connections.pending} — Gauge</li>
 *   <li>{@code hikaricp.connections.acquire} — Timer (acquire time)</li>
 *   <li>{@code process.cpu.usage} — Gauge</li>
 *   <li>{@code jvm.gc.pause} — Timer</li>
 * </ul>
 */
@Slf4j
public class MicrometerMetricsProvider implements MetricsProvider {

    /**
     * Minimum request count per window to consider the traffic "real"
     * (not just health-checks/actuator noise).  Below this, latency
     * is reported as 0 even if Micrometer histogram has stale data.
     */
    private static final int MIN_REQUESTS_PER_WINDOW = 5;

    private final MeterRegistry registry;
    private final String hikariPoolName;
    private final WindowedLatencyTracker latencyTracker;

    private long prevTotalRequests = 0;
    private double prevTotalTimeMs = 0;
    private long prevErrorRequests = 0;
    private long prevTimeoutCount = 0;
    private long prevSnapshotTimeNs = 0;
    private long prevAcquireCount = 0;

    /**
     * Full constructor.
     *
     * @param registry       the Micrometer registry to read meters from
     * @param hikariPoolName HikariCP pool name (meter tag), e.g. "HikariPool-1"
     * @param latencyTracker per-window latency tracker (may be {@code null} for
     *                       Micrometer-only fallback)
     */
    public MicrometerMetricsProvider(MeterRegistry registry,
                                     String hikariPoolName,
                                     WindowedLatencyTracker latencyTracker) {
        this.registry = registry;
        this.hikariPoolName = hikariPoolName != null ? hikariPoolName : "HikariPool-1";
        this.latencyTracker = latencyTracker;
    }

    public MicrometerMetricsProvider(MeterRegistry registry, String hikariPoolName) {
        this(registry, hikariPoolName, null);
    }

    public MicrometerMetricsProvider(MeterRegistry registry) {
        this(registry, "HikariPool-1", null);
    }

    @Override
    public MetricsSnapshot snapshot() {
        MetricsSnapshot.Builder b = MetricsSnapshot.builder();
        long now = System.nanoTime();

        // ── Read Micrometer cumulative counters (always, for throughput AND baseline sync) ──
        Collection<Timer> httpTimers = registry.find("http.server.requests").timers();
        long totalRequestsNow = 0;
        double totalTimeNowMs = 0;
        for (Timer t : httpTimers) {
            totalRequestsNow += t.count();
            totalTimeNowMs += t.totalTime(TimeUnit.MILLISECONDS);
        }
        long requestsDelta = totalRequestsNow - prevTotalRequests;

        // ── HTTP latency ────────────────────────────────────────────────
        WindowStats windowStats = latencyTracker != null
                ? latencyTracker.drainAndCompute()
                : WindowStats.EMPTY;

        if (windowStats.hasData()) {
            b.put(MetricNames.LATENCY_P95_MS, round3(windowStats.p95Ms()));
            b.put(MetricNames.LATENCY_P99_MS, round3(windowStats.p99Ms()));
            b.put(MetricNames.LATENCY_MEAN_MS, round3(windowStats.meanMs()));
        } else if (requestsDelta >= MIN_REQUESTS_PER_WINDOW) {
            // Enough real traffic but tracker had no data (non-Spring usage).
            // Compute mean from Micrometer totalTime delta (per-window).
            double timeDeltaMs = totalTimeNowMs - prevTotalTimeMs;
            double mean = round3(timeDeltaMs / requestsDelta);
            b.put(MetricNames.LATENCY_P95_MS, 0.0);
            b.put(MetricNames.LATENCY_P99_MS, 0.0);
            b.put(MetricNames.LATENCY_MEAN_MS, mean);
        } else {
            // No tracker data, and request count below noise threshold.
            // Noise traffic (health checks) — report zeros, not stale histogram.
            b.put(MetricNames.LATENCY_P95_MS, 0.0);
            b.put(MetricNames.LATENCY_P99_MS, 0.0);
            b.put(MetricNames.LATENCY_MEAN_MS, 0.0);
        }

        // Always keep prevTotalTimeMs in sync to avoid drift when
        // switching between tracker and fallback paths.
        prevTotalTimeMs = totalTimeNowMs;

        // ── HTTP throughput ─────────────────────────────────────────────
        if (!httpTimers.isEmpty()) {
            double elapsedSec = prevSnapshotTimeNs > 0
                    ? (now - prevSnapshotTimeNs) / 1_000_000_000.0
                    : 1.0;

            long errorNow = countErrors();
            long timeoutNow = countTimeouts();

            long errorsDelta = errorNow - prevErrorRequests;
            long timeoutsDelta = timeoutNow - prevTimeoutCount;

            if (requestsDelta < 0 || errorsDelta < 0 || timeoutsDelta < 0) {
                log.warn("Counter reset detected (reqΔ={}, errΔ={}, toutΔ={}), resetting baseline",
                        requestsDelta, errorsDelta, timeoutsDelta);
                requestsDelta = 0;
                errorsDelta = 0;
                timeoutsDelta = 0;
            }

            double goodput = elapsedSec > 0
                    ? Math.max(0, requestsDelta - errorsDelta) / elapsedSec
                    : 0;
            double errorRate = requestsDelta > 0 ? (double) errorsDelta / requestsDelta : 0;
            double timeoutRate = requestsDelta > 0 ? (double) timeoutsDelta / requestsDelta : 0;

            b.put(MetricNames.GOODPUT_RPS, round3(goodput));
            b.put(MetricNames.ERROR_RATE, round3(errorRate));
            b.put(MetricNames.TIMEOUT_RATE, round3(timeoutRate));

            prevTotalRequests = totalRequestsNow;
            prevErrorRequests = errorNow;
            prevTimeoutCount = timeoutNow;
        } else {
            prevTotalRequests = 0;
            prevTotalTimeMs = 0;
            b.put(MetricNames.GOODPUT_RPS, 0.0);
            b.put(MetricNames.ERROR_RATE, 0.0);
            b.put(MetricNames.TIMEOUT_RATE, 0.0);
        }

        // ── Inflight ────────────────────────────────────────────────────
        Gauge inflight = findGauge("iasr.concurrency.inflight");
        b.put(MetricNames.INFLIGHT, inflight != null ? inflight.value() : 0.0);

        // ── CPU ─────────────────────────────────────────────────────────
        Gauge cpuGauge = findGauge("process.cpu.usage");
        b.put(MetricNames.CPU_PROCESS, cpuGauge != null ? round3(cpuGauge.value()) : 0.0);

        // ── GC pause ────────────────────────────────────────────────────
        Collection<Timer> gcTimers = registry.find("jvm.gc.pause").timers();
        double gcP95 = 0;
        for (Timer t : gcTimers) {
            double max = t.takeSnapshot().max(TimeUnit.MILLISECONDS);
            gcP95 = Math.max(gcP95, max > 0 ? round3(max) : 0.0);
        }
        b.put(MetricNames.GC_PAUSE_P95_MS, gcP95);

        // ── Hikari metrics ──────────────────────────────────────────────
        Gauge hActive  = findHikariGauge("hikaricp.connections.active");
        Gauge hIdle    = findHikariGauge("hikaricp.connections.idle");
        Gauge hPending = findHikariGauge("hikaricp.connections.pending");

        b.put(MetricNames.HIKARI_ACTIVE,  hActive  != null ? hActive.value()  : 0.0);
        b.put(MetricNames.HIKARI_IDLE,    hIdle    != null ? hIdle.value()    : 0.0);
        b.put(MetricNames.HIKARI_PENDING, hPending != null ? hPending.value() : 0.0);

        Collection<Timer> acquireTimers = registry.find("hikaricp.connections.acquire")
                .tag("pool", hikariPoolName).timers();
        double acquireP95 = 0;
        long acquireCountNow = 0;
        for (Timer t : acquireTimers) {
            acquireCountNow += t.count();
        }
        long acquireDelta = acquireCountNow - prevAcquireCount;
        if (acquireDelta >= MIN_REQUESTS_PER_WINDOW) {
            for (Timer t : acquireTimers) {
                double max = t.takeSnapshot().max(TimeUnit.MILLISECONDS);
                acquireP95 = Math.max(acquireP95, max > 0 ? round3(max) : 0.0);
            }
        }
        prevAcquireCount = acquireCountNow;
        b.put(MetricNames.HIKARI_ACQUIRE_P95_MS, acquireP95);

        prevSnapshotTimeNs = now;
        return b.build();
    }

    // ── Meter lookup helpers ─────────────────────────────────────────────

    private Gauge findGauge(String name) {
        try {
            return registry.find(name).gauge();
        } catch (Exception e) {
            return null;
        }
    }

    private Gauge findHikariGauge(String name) {
        try {
            return registry.find(name).tag("pool", hikariPoolName).gauge();
        } catch (Exception e) {
            return null;
        }
    }

    private long countErrors() {
        try {
            Counter c = registry.find("iasr.http.errors").counter();
            return c != null ? (long) c.count() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long countTimeouts() {
        try {
            Counter c = registry.find("iasr.concurrency.timeouts").counter();
            return c != null ? (long) c.count() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
