package com.iasr.micrometer;

import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsProvider;
import com.iasr.core.metrics.MetricsSnapshot;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * {@link MetricsProvider} backed by Micrometer's {@link MeterRegistry}.
 * <p>
 * It reads well-known Micrometer meters that are typically registered by
 * Spring Boot Actuator, HikariCP metrics binder and custom IASR meters.
 *
 * <h3>Expected meters</h3>
 * <ul>
 *   <li>{@code http.server.requests} — Timer (latency, goodput, errors)</li>
 *   <li>{@code iasr.concurrency.inflight} — Gauge</li>
 *   <li>{@code iasr.concurrency.timeouts} — Counter</li>
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

    private final MeterRegistry registry;
    private final String hikariPoolName;

    /** Total request count at previous snapshot — used to compute RPS delta. */
    private long prevTotalRequests = 0;
    private long prevErrorRequests = 0;
    private long prevTimeoutCount = 0;
    private long prevSnapshotTimeNs = 0;

    /**
     * @param registry       the Micrometer registry to read meters from
     * @param hikariPoolName HikariCP pool name (used as meter tag), e.g. "HikariPool-1"
     */
    public MicrometerMetricsProvider(MeterRegistry registry, String hikariPoolName) {
        this.registry = registry;
        this.hikariPoolName = hikariPoolName != null ? hikariPoolName : "HikariPool-1";
    }

    public MicrometerMetricsProvider(MeterRegistry registry) {
        this(registry, "HikariPool-1");
    }

    @Override
    public MetricsSnapshot snapshot() {
        MetricsSnapshot.Builder b = MetricsSnapshot.builder();
        long now = System.nanoTime();

        // ── HTTP latency & throughput ────────────────────────────────────
        Timer httpTimer = findTimer("http.server.requests");
        if (httpTimer != null) {
            HistogramSnapshot hs = httpTimer.takeSnapshot();

            double p95ms = percentileMs(hs, 0.95);
            double p99ms = percentileMs(hs, 0.99);
            b.put(MetricNames.LATENCY_P95_MS, p95ms);
            b.put(MetricNames.LATENCY_P99_MS, p99ms);

            long totalNow = httpTimer.count();
            double elapsedSec = prevSnapshotTimeNs > 0
                    ? (now - prevSnapshotTimeNs) / 1_000_000_000.0
                    : 1.0;

            // Error count from tagged subset
            long errorNow = countErrors();
            long timeoutNow = countTimeouts();

            long requestsDelta = totalNow - prevTotalRequests;
            long errorsDelta = errorNow - prevErrorRequests;
            long timeoutsDelta = timeoutNow - prevTimeoutCount;

            double goodput = elapsedSec > 0 ? Math.max(0, requestsDelta - errorsDelta) / elapsedSec : 0;
            double errorRate = requestsDelta > 0 ? (double) errorsDelta / requestsDelta : 0;
            double timeoutRate = requestsDelta > 0 ? (double) timeoutsDelta / requestsDelta : 0;

            b.put(MetricNames.GOODPUT_RPS, round3(goodput));
            b.put(MetricNames.ERROR_RATE, round3(errorRate));
            b.put(MetricNames.TIMEOUT_RATE, round3(timeoutRate));

            prevTotalRequests = totalNow;
            prevErrorRequests = errorNow;
            prevTimeoutCount = timeoutNow;
        }

        // ── Inflight ────────────────────────────────────────────────────
        Gauge inflight = findGauge("iasr.concurrency.inflight");
        if (inflight != null) {
            b.put(MetricNames.INFLIGHT, inflight.value());
        }

        // ── CPU ─────────────────────────────────────────────────────────
        Gauge cpuGauge = findGauge("process.cpu.usage");
        if (cpuGauge != null) {
            b.put(MetricNames.CPU_PROCESS, round3(cpuGauge.value()));
        }

        // ── GC pause ────────────────────────────────────────────────────
        Timer gcTimer = findTimer("jvm.gc.pause");
        if (gcTimer != null) {
            double gcP95 = percentileMs(gcTimer.takeSnapshot(), 0.95);
            b.put(MetricNames.GC_PAUSE_P95_MS, gcP95);
        }

        // ── Hikari metrics ──────────────────────────────────────────────
        Gauge hActive = findHikariGauge("hikaricp.connections.active");
        Gauge hIdle   = findHikariGauge("hikaricp.connections.idle");
        Gauge hPending = findHikariGauge("hikaricp.connections.pending");

        if (hActive != null)  b.put(MetricNames.HIKARI_ACTIVE, hActive.value());
        if (hIdle != null)    b.put(MetricNames.HIKARI_IDLE, hIdle.value());
        if (hPending != null) b.put(MetricNames.HIKARI_PENDING, hPending.value());

        Timer acquireTimer = findHikariTimer("hikaricp.connections.acquire");
        if (acquireTimer != null) {
            double acquireP95 = percentileMs(acquireTimer.takeSnapshot(), 0.95);
            b.put(MetricNames.HIKARI_ACQUIRE_P95_MS, acquireP95);
        }

        prevSnapshotTimeNs = now;
        return b.build();
    }

    // ── Meter lookup helpers ─────────────────────────────────────────────

    private Timer findTimer(String name) {
        try {
            return registry.find(name).timer();
        } catch (Exception e) {
            return null;
        }
    }

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

    private Timer findHikariTimer(String name) {
        try {
            return registry.find(name).tag("pool", hikariPoolName).timer();
        } catch (Exception e) {
            return null;
        }
    }

    private long countErrors() {
        // Count 5xx status responses
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

    // ── Percentile extraction ────────────────────────────────────────────

    private static double percentileMs(HistogramSnapshot hs, double percentile) {
        if (hs == null) return 0.0;
        ValueAtPercentile[] vals = hs.percentileValues();
        if (vals == null || vals.length == 0) return 0.0;
        for (ValueAtPercentile v : vals) {
            if (Math.abs(v.percentile() - percentile) < 0.01) {
                return round3(v.value(TimeUnit.MILLISECONDS));
            }
        }
        // Fallback: return max as rough estimate
        return round3(hs.max(TimeUnit.MILLISECONDS));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
