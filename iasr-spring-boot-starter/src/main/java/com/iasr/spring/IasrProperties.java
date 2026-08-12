package com.iasr.spring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for IASR Spring Boot integration.
 * <p>
 * Prefix: {@code iasr}
 *
 * <pre>
 * iasr:
 *   enabled: true          # master switch — false disables IASR entirely
 *   apply-actions: true    # false = observe-only (metrics + dataset, no changes)
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
 *     block-increase-on-slo-violation: true
 *   hikari:
 *     min-pool-size: 5
 *     max-pool-size: 50
 *     max-step: 2
 *     cooldown-ticks: 5
 *     block-increase-on-slo-violation: false
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
@ConfigurationProperties(prefix = "iasr")
public class IasrProperties {

    /**
     * Master switch.  When {@code false} the whole auto-configuration backs
     * off and no IASR beans are created at all.
     */
    private boolean enabled = true;

    /**
     * When {@code false}, the control loop runs in <b>observe-only</b> mode:
     * metrics are collected and the dataset is written, but no actuator is
     * ever changed.  Intended for safely introducing IASR into production and
     * for collecting a training dataset under an existing external policy.
     * <p>
     * This is deliberately separate from {@link #enabled}: setting
     * {@code enabled: false} switches IASR off entirely, which would also
     * stop the dataset collection that observe-only mode exists to provide.
     */
    private boolean applyActions = true;

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

        /**
         * Largest reduction allowed in one tick; {@code 0} means "same as
         * {@link #maxStep}".
         * <p>
         * Deliberately larger than {@code maxStep} by default. Admitting more
         * traffic too quickly can cause an incident, so growth is rate-limited;
         * shedding is how the loop escapes one, and capping both at the same
         * rate would throttle the emergency brake as hard as the accelerator.
         */
        private int maxStepDown = 50;

        private int cooldownTicks = 3;
        private long acquireTimeoutMs = 500;

        /**
         * Block concurrency increases while the SLO is violated.  Defaults to
         * {@code true}: admitting more concurrent requests when latency is
         * already over target normally makes the violation worse.
         */
        private boolean blockIncreaseOnSloViolation = true;
    }

    // ── Nested: Hikari ───────────────────────────────────────────────────

    @Getter
    @Setter
    public static class Hikari {
        private int minPoolSize = 5;
        private int maxPoolSize = 50;
        private int maxStep = 2;
        private int cooldownTicks = 5;

        /**
         * Block pool increases while the SLO is violated.  Defaults to
         * {@code false}, unlike concurrency: threads queued waiting for a
         * database connection surface as high request latency, so a connection
         * shortage is a plausible <em>cause</em> of the violation and growing
         * the pool is the remedy.  Blocking it would trap the control loop in
         * the state it is trying to escape.  The controller only asks for an
         * increase when it sees both pending threads and high acquire time.
         */
        private boolean blockIncreaseOnSloViolation = false;
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

        /**
         * Fraction of the concurrency limit that must be in use before the
         * limit may grow; {@code 0} disables the gate.
         * <p>
         * Additive increase without this has no notion of "enough" — it raises
         * the limit on every comfortable tick regardless of whether anything is
         * waiting for a permit, so a healthy service ratchets its limit to the
         * ceiling and meets the next surge with no admission control left.
         */
        private double utilizationGate = 0.9;

        /**
         * Multiplier on the Little's-law concurrency estimate
         * ({@code offered rate × SLO}) used as a growth ceiling and as the
         * target to descend to when the SLO is violated; {@code 0} disables it.
         * <p>
         * Cuts p95 by 37-44% in two of three benchmark scenarios at no
         * measurable cost to throughput, and improves the share of responses
         * meeting the SLO in all three. Under a slow ramp past capacity it
         * trades about 7% of throughput for 40% of the tail — set to 0 where
         * throughput matters more.
         */
        private double sloTargetHeadroom = 1.0;

        /**
         * Floor on the latency gradient {@code SLO / observed} used to shrink
         * the concurrency limit; {@code 0} falls back to the fixed
         * {@link #concurrencyDecreaseFactor}.
         * <p>
         * Makes the correction proportional to the error instead of a fixed
         * fraction: latency at three times the target divides the limit by
         * three. The floor bounds how much a single tick may remove.
         * <p>
         * The default of 0.9 caps a single cut at 10%, gentler than the fixed
         * factor it replaces — and it measured better than 0.8 and 0.5 on the
         * benchmark. Proportionality, not aggression, is what helps: the cut
         * shrinks as latency approaches the SLO, so the loop stops instead of
         * overshooting, whereas an aggressive floor turns the loop into a
         * ratchet downwards and sheds traffic it did not need to.
         */
        private double minGradient = 0.9;

        /**
         * Idle connections that must be sitting spare before the pool is
         * eligible to shrink.
         * <p>
         * Without this, a pool sized exactly right — no queue, no spare
         * connections — is shrunk anyway until it becomes the bottleneck.
         */
        private double hikariIdleShrinkThreshold = 2.0;
    }
}
