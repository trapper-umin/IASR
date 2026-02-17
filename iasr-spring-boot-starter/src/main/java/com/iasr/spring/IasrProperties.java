package com.iasr.spring;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration properties for IASR Spring Boot integration.
 * <p>
 * Prefix: {@code iasr}
 *
 * <pre>
 * iasr:
 *   enabled: true
 *   dataset-logging-enabled: true
 *   slo-latency-ms: 200
 *   window-ms: 5000
 *   concurrency:
 *     initial-limit: 100
 *     min-limit: 10
 *     max-limit: 500
 *     max-step: 20
 *     cooldown-ticks: 3
 *     acquire-timeout-ms: 500
 *   hikari:
 *     min-pool-size: 5
 *     max-pool-size: 50
 *     max-step: 2
 *     cooldown-ticks: 5
 *   controller:
 *     comfort-factor: 0.7
 *     concurrency-increase-step: 5
 *     concurrency-decrease-factor: 0.85
 *     error-rate-threshold: 0.01
 *     timeout-rate-threshold: 0.005
 *     hikari-step: 1
 *     hikari-pending-threshold: 0
 *     hikari-acquire-time-threshold-ms: 10
 * </pre>
 */
@Getter
@Setter
public class IasrProperties {

    private boolean enabled = true;
    private boolean datasetLoggingEnabled = true;
    private double sloLatencyMs = 200.0;
    private long windowMs = 5000;

    private final Concurrency concurrency = new Concurrency();
    private final Hikari hikari = new Hikari();
    private final ControllerProps controller = new ControllerProps();

    // ── Nested: Concurrency ──────────────────────────────────────────────

    @Getter
    @Setter
    public static class Concurrency {
        private int initialLimit = 100;
        private int minLimit = 10;
        private int maxLimit = 500;
        private int maxStep = 20;
        private int cooldownTicks = 3;
        private long acquireTimeoutMs = 500;
    }

    // ── Nested: Hikari ───────────────────────────────────────────────────

    @Getter
    @Setter
    public static class Hikari {
        private int minPoolSize = 5;
        private int maxPoolSize = 50;
        private int maxStep = 2;
        private int cooldownTicks = 5;
    }

    // ── Nested: Controller tuning ────────────────────────────────────────

    @Getter
    @Setter
    public static class ControllerProps {
        private double comfortFactor = 0.7;
        private int concurrencyIncreaseStep = 5;
        private double concurrencyDecreaseFactor = 0.85;
        private double errorRateThreshold = 0.01;
        private double timeoutRateThreshold = 0.005;
        private int hikariStep = 1;
        private double hikariPendingThreshold = 0.0;
        private double hikariAcquireTimeThresholdMs = 10.0;
    }
}
