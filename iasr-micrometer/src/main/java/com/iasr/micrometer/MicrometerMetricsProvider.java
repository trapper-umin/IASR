package com.iasr.micrometer;

import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsProvider;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.metrics.WindowedLatencyTracker;
import com.iasr.core.metrics.WindowedLatencyTracker.WindowStats;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>{@code iasr.http.requests}, {@code iasr.http.successes} — preferred business counters</li>
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

    /** How close a published percentile must be to the requested one to be used. */
    private static final double PERCENTILE_TOLERANCE = 1e-6;

    /** Pool name HikariCP generates for the first pool created in a JVM. */
    static final String DEFAULT_POOL_NAME = "HikariPool-1";

    private final MeterRegistry registry;
    private final Supplier<String> poolNameSupplier;
    private volatile String resolvedPoolName;
    private final WindowedLatencyTracker latencyTracker;

    /** Meter names already reported as missing percentile config (warn once each). */
    private final Set<String> missingPercentilesWarned = ConcurrentHashMap.newKeySet();

    private long prevTotalRequests = 0;
    private long prevSuccessfulRequests = 0;
    private long prevHttpRequests = 0;
    private Boolean previousBusinessCounters;
    private double prevTotalTimeMs = 0;
    private long prevErrorRequests = 0;
    private long prevTimeoutCount = 0;
    private long prevSnapshotTimeNs = Long.MIN_VALUE;
    private long prevAcquireCount = 0;
    private double prevAcquireTotalTimeMs = 0;
    private long prevAdmissionWaitCount = 0;
    private double prevAdmissionWaitTotalMs = 0;

    /**
     * Full constructor.
     *
     * @param registry       the Micrometer registry to read meters from
     * @param hikariPoolName HikariCP pool name (meter tag), e.g. "HikariPool-1";
     *                       {@code null} falls back to {@value #DEFAULT_POOL_NAME}
     * @param latencyTracker per-window latency tracker (may be {@code null} for
     *                       Micrometer-only fallback)
     */
    public MicrometerMetricsProvider(MeterRegistry registry,
                                     String hikariPoolName,
                                     WindowedLatencyTracker latencyTracker) {
        this(registry, latencyTracker, () -> hikariPoolName);
    }

    private MicrometerMetricsProvider(MeterRegistry registry,
                                      WindowedLatencyTracker latencyTracker,
                                      Supplier<String> poolNameSupplier) {
        this.registry = registry;
        this.poolNameSupplier = poolNameSupplier != null ? poolNameSupplier : () -> null;
        this.latencyTracker = latencyTracker;
    }

    /**
     * Create a provider that resolves the Hikari pool name lazily.
     * <p>
     * HikariCP only assigns a generated name when the pool actually starts, so
     * a name read while the Spring context is still wiring up is often
     * {@code null}.  Resolving on first use instead lets the real name be
     * picked up once the pool is live; the result is cached after the first
     * non-null answer.
     * <p>
     * Deliberately a factory method rather than another three-argument
     * constructor: an overload taking {@code Supplier<String>} alongside one
     * taking {@code String} makes every existing {@code (registry, null,
     * tracker)} call ambiguous, which would break source compatibility for
     * callers that pass an explicit null.
     *
     * @param poolNameSupplier supplies the pool name; may return {@code null}
     *                         until the pool has started
     */
    public static MicrometerMetricsProvider withPoolNameSupplier(
            MeterRegistry registry,
            Supplier<String> poolNameSupplier,
            WindowedLatencyTracker latencyTracker) {
        return new MicrometerMetricsProvider(registry, latencyTracker, poolNameSupplier);
    }

    public MicrometerMetricsProvider(MeterRegistry registry, String hikariPoolName) {
        this(registry, hikariPoolName, null);
    }

    public MicrometerMetricsProvider(MeterRegistry registry) {
        this(registry, (String) null, null);
    }

    /**
     * The pool name to tag Hikari meter lookups with, resolved on first use and
     * cached thereafter.
     */
    String hikariPoolName() {
        String cached = resolvedPoolName;
        if (cached != null) {
            return cached;
        }
        String resolved = poolNameSupplier.get();
        if (resolved == null || resolved.isBlank()) {
            // Pool not started yet — use the conventional name for this window
            // and try again next tick rather than caching a guess forever.
            return DEFAULT_POOL_NAME;
        }
        resolvedPoolName = resolved;
        return resolved;
    }

    @Override
    public MetricsSnapshot snapshot() {
        MetricsSnapshot.Builder b = MetricsSnapshot.builder();
        long now = registry.config().clock().monotonicTime();
        double serviceMeanMs = 0;

        // ── Read Micrometer cumulative counters (always, for throughput AND baseline sync) ──
        Collection<Timer> httpTimers = registry.find("http.server.requests").timers();
        long httpRequestsNow = 0;
        long httpSuccessesNow = 0;
        long httpErrorsNow = 0;
        boolean hasStatusTags = false;
        double totalTimeNowMs = 0;
        for (Timer t : httpTimers) {
            httpRequestsNow += t.count();
            totalTimeNowMs += t.totalTime(TimeUnit.MILLISECONDS);
            String status = t.getId().getTag("status");
            if (status != null) {
                hasStatusTags = true;
                if (status.matches("2\\d{2}")) httpSuccessesNow += t.count();
                if (status.matches("5\\d{2}")) httpErrorsNow += t.count();
            }
        }
        long httpRequestsDelta = httpRequestsNow - prevHttpRequests;
        prevHttpRequests = httpRequestsNow;

        // The servlet filter counts business responses directly, excluding probes.
        // Outside the starter, prefer explicit HTTP statuses. Untagged legacy
        // timers can only approximate success by subtracting known failures.
        Counter businessRequests = registry.find("iasr.http.requests").counter();
        boolean useBusinessCounters = businessRequests != null;
        long totalRequestsNow = useBusinessCounters ? (long) businessRequests.count() : httpRequestsNow;
        long errorNow = useBusinessCounters || registry.find("iasr.http.errors").counter() != null
                ? countErrors() : httpErrorsNow;
        long timeoutNow = countTimeouts();
        long successfulRequestsNow = useBusinessCounters
                ? countCounter("iasr.http.successes")
                : hasStatusTags ? httpSuccessesNow
                : Math.max(0, httpRequestsNow - errorNow - timeoutNow);
        long requestsDelta = totalRequestsNow - prevTotalRequests;
        long successesDelta = successfulRequestsNow - prevSuccessfulRequests;
        long errorsDelta = errorNow - prevErrorRequests;
        long timeoutsDelta = timeoutNow - prevTimeoutCount;

        boolean sourceChanged = previousBusinessCounters != null
                && previousBusinessCounters != useBusinessCounters;
        if (sourceChanged || requestsDelta < 0 || successesDelta < 0 || errorsDelta < 0 || timeoutsDelta < 0) {
            log.warn("HTTP counter source changed or reset; resetting rate baseline");
            requestsDelta = successesDelta = errorsDelta = timeoutsDelta = 0;
        }

        // ── HTTP latency ────────────────────────────────────────────────
        WindowStats windowStats = latencyTracker != null
                ? latencyTracker.drainAndCompute()
                : WindowStats.EMPTY;

        if (windowStats.hasData()) {
            b.put(MetricNames.LATENCY_P95_MS, round3(windowStats.p95Ms()));
            b.put(MetricNames.LATENCY_P99_MS, round3(windowStats.p99Ms()));
            serviceMeanMs = round3(windowStats.serviceMeanMs());
            b.put(MetricNames.LATENCY_MEAN_MS, round3(windowStats.meanMs()));
            b.put(MetricNames.SLO_GOOD_FRACTION, round3(windowStats.withinSloFraction()));
        } else if (latencyTracker == null && httpRequestsDelta >= MIN_REQUESTS_PER_WINDOW) {
            // No tracker (non-Spring usage): this mean includes every HTTP status.
            // Compute mean from Micrometer totalTime delta (per-window).
            double timeDeltaMs = totalTimeNowMs - prevTotalTimeMs;
            serviceMeanMs = round3(Math.max(0, timeDeltaMs) / httpRequestsDelta);
            b.put(MetricNames.LATENCY_P95_MS, 0.0);
            b.put(MetricNames.LATENCY_P99_MS, 0.0);
            b.put(MetricNames.LATENCY_MEAN_MS, serviceMeanMs);
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
        if (useBusinessCounters || !httpTimers.isEmpty()) {
            double elapsedSec = prevSnapshotTimeNs != Long.MIN_VALUE
                    ? (now - prevSnapshotTimeNs) / 1_000_000_000.0
                    : 1.0;

            double goodput = elapsedSec > 0
                    ? Math.max(0, successesDelta) / elapsedSec
                    : 0;
            double errorRate = requestsDelta > 0 ? Math.min(1, (double) errorsDelta / requestsDelta) : 0;
            double timeoutRate = requestsDelta > 0 ? Math.min(1, (double) timeoutsDelta / requestsDelta) : 0;

            b.put(MetricNames.GOODPUT_RPS, round3(goodput));
            b.put(MetricNames.ERROR_RATE, round3(errorRate));
            b.put(MetricNames.TIMEOUT_RATE, round3(timeoutRate));
        } else {
            b.put(MetricNames.GOODPUT_RPS, 0.0);
            b.put(MetricNames.ERROR_RATE, 0.0);
            b.put(MetricNames.TIMEOUT_RATE, 0.0);
        }
        prevTotalRequests = totalRequestsNow;
        prevSuccessfulRequests = successfulRequestsNow;
        prevErrorRequests = errorNow;
        prevTimeoutCount = timeoutNow;
        previousBusinessCounters = useBusinessCounters;

        // ── Inflight and permit queue ───────────────────────────────────
        Gauge inflight = findGauge("iasr.concurrency.inflight");
        b.put(MetricNames.INFLIGHT, inflight != null ? inflight.value() : 0.0);

        Gauge queue = findGauge("iasr.concurrency.queue");
        b.put(MetricNames.QUEUE_LENGTH, queue != null ? queue.value() : 0.0);

        // Per-window mean wait for a permit, from count and total-time deltas.
        double admissionWaitMean = 0;
        Collection<Timer> waitTimers = registry.find("iasr.concurrency.wait").timers();
        long waitCountNow = 0;
        double waitTotalNowMs = 0;
        for (Timer t : waitTimers) {
            waitCountNow += t.count();
            waitTotalNowMs += t.totalTime(TimeUnit.MILLISECONDS);
        }
        long waitDelta = waitCountNow - prevAdmissionWaitCount;
        double waitTimeDeltaMs = waitTotalNowMs - prevAdmissionWaitTotalMs;
        if (waitDelta >= MIN_REQUESTS_PER_WINDOW) {
            admissionWaitMean = round3(Math.max(0, waitTimeDeltaMs) / waitDelta);
        }
        prevAdmissionWaitCount = waitCountNow;
        prevAdmissionWaitTotalMs = waitTotalNowMs;
        b.put(MetricNames.ADMISSION_WAIT_MEAN_MS, admissionWaitMean);
        // Without a tracker the HTTP timer includes admission; this remains an
        // approximation because the wait timer can have a different population.
        if (latencyTracker == null) {
            serviceMeanMs = round3(Math.max(0, serviceMeanMs - admissionWaitMean));
        }
        b.put(MetricNames.SERVICE_MEAN_MS, serviceMeanMs);

        // ── CPU ─────────────────────────────────────────────────────────
        Gauge cpuGauge = findGauge("process.cpu.usage");
        b.put(MetricNames.CPU_PROCESS, cpuGauge != null ? round3(cpuGauge.value()) : 0.0);

        // ── GC pause ────────────────────────────────────────────────────
        Collection<Timer> gcTimers = registry.find("jvm.gc.pause").timers();
        b.put(MetricNames.GC_PAUSE_P95_MS, percentileMs(gcTimers, 0.95, "jvm.gc.pause"));

        // ── Hikari metrics ──────────────────────────────────────────────
        Gauge hActive  = findHikariGauge("hikaricp.connections.active");
        Gauge hIdle    = findHikariGauge("hikaricp.connections.idle");
        Gauge hPending = findHikariGauge("hikaricp.connections.pending");

        b.put(MetricNames.HIKARI_ACTIVE,  hActive  != null ? hActive.value()  : 0.0);
        b.put(MetricNames.HIKARI_IDLE,    hIdle    != null ? hIdle.value()    : 0.0);
        b.put(MetricNames.HIKARI_PENDING, hPending != null ? hPending.value() : 0.0);

        Collection<Timer> acquireTimers = registry.find("hikaricp.connections.acquire")
                .tag("pool", hikariPoolName()).timers();
        long acquireCountNow = 0;
        double acquireTotalTimeNowMs = 0;
        for (Timer t : acquireTimers) {
            acquireCountNow += t.count();
            acquireTotalTimeNowMs += t.totalTime(TimeUnit.MILLISECONDS);
        }
        long acquireDelta = acquireCountNow - prevAcquireCount;
        double acquireTimeDeltaMs = acquireTotalTimeNowMs - prevAcquireTotalTimeMs;

        double acquireP95 = 0;
        double acquireMean = 0;
        if (acquireDelta >= MIN_REQUESTS_PER_WINDOW) {
            acquireP95 = percentileMs(acquireTimers, 0.95, "hikaricp.connections.acquire");
            acquireMean = round3(Math.max(0, acquireTimeDeltaMs) / acquireDelta);
        }
        prevAcquireCount = acquireCountNow;
        prevAcquireTotalTimeMs = acquireTotalTimeNowMs;
        b.put(MetricNames.HIKARI_ACQUIRE_P95_MS, acquireP95);
        b.put(MetricNames.HIKARI_ACQUIRE_MEAN_MS, acquireMean);

        // Residual of the decomposition: whatever the request spent that was
        // neither waiting for a permit nor waiting for a connection.  Computed
        // on means because percentiles are not additive — a p95 minus a p95 is
        // not the p95 of the difference, and treating it as one would produce a
        // number that describes nothing.
        b.put(MetricNames.DEPENDENCY_MEAN_MS, round3(Math.max(0, serviceMeanMs - acquireMean)));

        prevSnapshotTimeNs = now;
        return b.build();
    }

    // ── Percentile extraction ────────────────────────────────────────────

    /**
     * Reads the value at percentile {@code p} from the given timers'
     * histogram snapshots.
     * <p>
     * Percentile values are only published when the registry is configured
     * with a percentile histogram for the meter — that is what
     * {@link IasrMeterFilterConfigurer} does.  If no matching percentile is
     * published, this falls back to the snapshot {@code max} (an
     * over-estimate) and warns once per meter name, rather than silently
     * reporting zero.
     * <p>
     * When several timers match (one per tag combination), their percentiles
     * are combined by taking the maximum.  Histograms cannot be merged
     * correctly after the fact, so this is a deliberate worst-case
     * approximation.
     * <p>
     * Note that Micrometer computes these percentiles over its own decaying
     * window (~2 min by default), not over the IASR control window Δt.  For
     * HTTP latency this is solved by {@link WindowedLatencyTracker}; for GC
     * and connection-acquire timings the decaying value is the best available
     * signal without instrumenting those call sites directly.
     *
     * @return the percentile in milliseconds, rounded; {@code 0.0} if no timers matched
     */
    private double percentileMs(Collection<Timer> timers, double p, String meterName) {
        if (timers.isEmpty()) {
            return 0.0;
        }

        double fromPercentiles = 0;
        double fromMax = 0;
        boolean percentilePublished = false;

        for (Timer t : timers) {
            HistogramSnapshot snap = t.takeSnapshot();

            double max = snap.max(TimeUnit.MILLISECONDS);
            if (max > 0) {
                fromMax = Math.max(fromMax, max);
            }

            for (ValueAtPercentile vp : snap.percentileValues()) {
                if (Math.abs(vp.percentile() - p) <= PERCENTILE_TOLERANCE) {
                    percentilePublished = true;
                    double v = vp.value(TimeUnit.MILLISECONDS);
                    if (v > 0) {
                        fromPercentiles = Math.max(fromPercentiles, v);
                    }
                }
            }
        }

        if (percentilePublished) {
            return round3(fromPercentiles);
        }

        if (missingPercentilesWarned.add(meterName)) {
            log.warn("Meter [{}] publishes no p{} value — falling back to max, which over-estimates. "
                            + "Register IasrMeterFilterConfigurer.createFilter() on the MeterRegistry "
                            + "to enable percentile histograms.",
                    meterName, Math.round(p * 100));
        }
        return round3(fromMax);
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
            return registry.find(name).tag("pool", hikariPoolName()).gauge();
        } catch (Exception e) {
            return null;
        }
    }

    private long countErrors() {
        return countCounter("iasr.http.errors");
    }

    private long countTimeouts() {
        return countCounter("iasr.concurrency.timeouts");
    }

    private long countCounter(String name) {
        try {
            Counter c = registry.find(name).counter();
            return c != null ? (long) c.count() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
