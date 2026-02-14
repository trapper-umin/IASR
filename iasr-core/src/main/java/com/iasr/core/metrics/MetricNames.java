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

    // ── Latency ──────────────────────────────────────────────────────────
    public static final String LATENCY_P95_MS       = "latency_p95_ms";
    public static final String LATENCY_P99_MS       = "latency_p99_ms";

    // ── Throughput ───────────────────────────────────────────────────────
    public static final String GOODPUT_RPS          = "goodput_rps";
    public static final String ERROR_RATE           = "error_rate";
    public static final String TIMEOUT_RATE         = "timeout_rate";

    // ── Concurrency ─────────────────────────────────────────────────────
    public static final String INFLIGHT             = "inflight";

    // ── JVM / System ────────────────────────────────────────────────────
    public static final String CPU_PROCESS          = "cpu_proc";
    public static final String GC_PAUSE_P95_MS      = "gc_pause_p95_ms";

    // ── HikariCP ────────────────────────────────────────────────────────
    public static final String HIKARI_ACTIVE        = "hikari_active";
    public static final String HIKARI_IDLE          = "hikari_idle";
    public static final String HIKARI_PENDING       = "hikari_pending";
    public static final String HIKARI_ACQUIRE_P95_MS = "hikari_acquire_p95_ms";
}
