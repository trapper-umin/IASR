package com.iasr.core.metrics;

/**
 * Well-known metric key constants used by the baseline controller and dataset logger.
 * <p>
 * Adapters (e.g. Micrometer) should publish metrics under these keys so that
 * the built-in {@link com.iasr.core.controller.BaselineController} can read them.
 * Custom controllers may use any keys they wish.
 */
public final class MetricNames {

    private MetricNames() {}

    /** Engine decision index, including warmup. Independent of wall-clock time. */
    public static final String CONTROL_TICK = "control_tick";
    public static final String CONTROL_WINDOW_SEC = "control_window_sec";

    public static String cooldownRemaining(String actuatorName) {
        return "cooldown_remaining." + actuatorName;
    }

    // Successful-request latency from admission entry to response completion.
    // Includes admission wait; excludes client/network and server accept queues.
    public static final String LATENCY_P95_MS       = "latency_p95_ms";
    public static final String LATENCY_P99_MS       = "latency_p99_ms";
    public static final String LATENCY_MEAN_MS      = "latency_mean_ms";
    /** Successful-request duration after admission, including connection acquisition. */
    public static final String SERVICE_MEAN_MS      = "service_mean_ms";

    // ── Throughput ───────────────────────────────────────────────────────
    public static final String GOODPUT_RPS          = "goodput_rps";
    public static final String ERROR_RATE           = "error_rate";
    public static final String TIMEOUT_RATE         = "timeout_rate";

    // ── Concurrency ─────────────────────────────────────────────────────
    public static final String INFLIGHT             = "inflight";

    /**
     * Threads currently blocked waiting for a concurrency permit.
     * <p>
     * The most direct demand signal available: a non-zero queue means the limit
     * is provably the binding constraint, whereas high in-flight counts only
     * suggest it.  Reading utilisation instead forces a threshold ("is 90% of
     * the limit in use?") that has to be guessed; a waiting thread is a fact.
     */
    public static final String QUEUE_LENGTH         = "queue_length";

    /**
     * Mean time a served request spent waiting for a concurrency permit.
     * <p>
     * Not the same as {@link #QUEUE_LENGTH}: the queue says how many are
     * waiting, this says how long the wait cost. Latency measured from the
     * moment a permit is granted excludes it entirely, so without this term the
     * end-to-end time a client experiences cannot be accounted for.
     */
    public static final String ADMISSION_WAIT_MEAN_MS = "admission_wait_mean_ms";

    /**
     * Mean time inside the downstream dependency, with the connection wait
     * removed: {@code service_mean_ms - hikari_acquire_mean_ms}.
     * <p>
     * An approximation: includes application work, and connection-acquire and
     * HTTP samples can belong to different completion windows.
     */
    public static final String DEPENDENCY_MEAN_MS   = "dependency_mean_ms";

    // ── JVM / System ────────────────────────────────────────────────────
    public static final String CPU_PROCESS          = "cpu_proc";
    public static final String GC_PAUSE_P95_MS      = "gc_pause_p95_ms";

    // ── HikariCP ────────────────────────────────────────────────────────
    public static final String HIKARI_ACTIVE        = "hikari_active";
    public static final String HIKARI_IDLE          = "hikari_idle";
    public static final String HIKARI_PENDING       = "hikari_pending";
    public static final String HIKARI_ACQUIRE_P95_MS = "hikari_acquire_p95_ms";

    /**
     * Mean connection-acquire time over the current control window, derived
     * from the per-window deltas of the Micrometer timer's count and total
     * time.  Unlike {@link #HIKARI_ACQUIRE_P95_MS}, which comes from a
     * decaying histogram, this value covers exactly one Δt window.
     */
    public static final String HIKARI_ACQUIRE_MEAN_MS = "hikari_acquire_mean_ms";
}
