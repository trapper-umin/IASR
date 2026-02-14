package com.iasr.spring;

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
public class IasrProperties {

    private boolean enabled = true;
    private boolean datasetLoggingEnabled = true;
    private double sloLatencyMs = 200.0;
    private long windowMs = 5000;

    private final Concurrency concurrency = new Concurrency();
    private final Hikari hikari = new Hikari();
    private final ControllerProps controller = new ControllerProps();

    // ── Getters / Setters ────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isDatasetLoggingEnabled() { return datasetLoggingEnabled; }
    public void setDatasetLoggingEnabled(boolean v) { this.datasetLoggingEnabled = v; }

    public double getSloLatencyMs() { return sloLatencyMs; }
    public void setSloLatencyMs(double sloLatencyMs) { this.sloLatencyMs = sloLatencyMs; }

    public long getWindowMs() { return windowMs; }
    public void setWindowMs(long windowMs) { this.windowMs = windowMs; }

    public Concurrency getConcurrency() { return concurrency; }
    public Hikari getHikari() { return hikari; }
    public ControllerProps getController() { return controller; }

    // ── Nested: Concurrency ──────────────────────────────────────────────

    public static class Concurrency {
        private int initialLimit = 100;
        private int minLimit = 10;
        private int maxLimit = 500;
        private int maxStep = 20;
        private int cooldownTicks = 3;
        private long acquireTimeoutMs = 500;

        public int getInitialLimit() { return initialLimit; }
        public void setInitialLimit(int v) { this.initialLimit = v; }

        public int getMinLimit() { return minLimit; }
        public void setMinLimit(int v) { this.minLimit = v; }

        public int getMaxLimit() { return maxLimit; }
        public void setMaxLimit(int v) { this.maxLimit = v; }

        public int getMaxStep() { return maxStep; }
        public void setMaxStep(int v) { this.maxStep = v; }

        public int getCooldownTicks() { return cooldownTicks; }
        public void setCooldownTicks(int v) { this.cooldownTicks = v; }

        public long getAcquireTimeoutMs() { return acquireTimeoutMs; }
        public void setAcquireTimeoutMs(long v) { this.acquireTimeoutMs = v; }
    }

    // ── Nested: Hikari ───────────────────────────────────────────────────

    public static class Hikari {
        private int minPoolSize = 5;
        private int maxPoolSize = 50;
        private int maxStep = 2;
        private int cooldownTicks = 5;

        public int getMinPoolSize() { return minPoolSize; }
        public void setMinPoolSize(int v) { this.minPoolSize = v; }

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int v) { this.maxPoolSize = v; }

        public int getMaxStep() { return maxStep; }
        public void setMaxStep(int v) { this.maxStep = v; }

        public int getCooldownTicks() { return cooldownTicks; }
        public void setCooldownTicks(int v) { this.cooldownTicks = v; }
    }

    // ── Nested: Controller tuning ────────────────────────────────────────

    public static class ControllerProps {
        private double comfortFactor = 0.7;
        private int concurrencyIncreaseStep = 5;
        private double concurrencyDecreaseFactor = 0.85;
        private double errorRateThreshold = 0.01;
        private double timeoutRateThreshold = 0.005;
        private int hikariStep = 1;
        private double hikariPendingThreshold = 0.0;
        private double hikariAcquireTimeThresholdMs = 10.0;

        public double getComfortFactor() { return comfortFactor; }
        public void setComfortFactor(double v) { this.comfortFactor = v; }

        public int getConcurrencyIncreaseStep() { return concurrencyIncreaseStep; }
        public void setConcurrencyIncreaseStep(int v) { this.concurrencyIncreaseStep = v; }

        public double getConcurrencyDecreaseFactor() { return concurrencyDecreaseFactor; }
        public void setConcurrencyDecreaseFactor(double v) { this.concurrencyDecreaseFactor = v; }

        public double getErrorRateThreshold() { return errorRateThreshold; }
        public void setErrorRateThreshold(double v) { this.errorRateThreshold = v; }

        public double getTimeoutRateThreshold() { return timeoutRateThreshold; }
        public void setTimeoutRateThreshold(double v) { this.timeoutRateThreshold = v; }

        public int getHikariStep() { return hikariStep; }
        public void setHikariStep(int v) { this.hikariStep = v; }

        public double getHikariPendingThreshold() { return hikariPendingThreshold; }
        public void setHikariPendingThreshold(double v) { this.hikariPendingThreshold = v; }

        public double getHikariAcquireTimeThresholdMs() { return hikariAcquireTimeThresholdMs; }
        public void setHikariAcquireTimeThresholdMs(double v) { this.hikariAcquireTimeThresholdMs = v; }
    }
}
