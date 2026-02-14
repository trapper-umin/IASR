package com.iasr.core.controller;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Deterministic baseline controller using <b>AIMD</b>-style threshold logic.
 * <p>
 * Decision rules (applied every tick):
 *
 * <h3>Concurrency limit</h3>
 * <ul>
 *   <li><b>Increase (+k)</b>: latency p95 &lt; 0.7 × SLO, error rate ≈ 0,
 *       timeout rate ≈ 0, no saturation signs → additive increase by {@code concurrencyIncreaseStep}</li>
 *   <li><b>Decrease (×β or −m)</b>: latency p95 &gt; SLO OR timeout rate rising
 *       OR error rate rising → multiplicative decrease by {@code concurrencyDecreaseFactor}
 *       (e.g. 0.85)</li>
 *   <li><b>Hold</b>: otherwise</li>
 * </ul>
 *
 * <h3>Hikari pool size</h3>
 * <ul>
 *   <li><b>Increase (+1)</b>: pending &gt; 0 AND acquire time p95 is rising,
 *       AND latency p95 is not in violation zone</li>
 *   <li><b>Decrease (−1)</b>: no pending for sustained period OR DB latency
 *       degrades after a pool increase</li>
 *   <li><b>Hold</b>: otherwise (conservative by default)</li>
 * </ul>
 *
 * <p>This controller is designed to be <b>explainable</b>: every decision is
 * traceable to simple threshold comparisons with named parameters.
 */
public class BaselineController implements Controller {

    private static final Logger log = LoggerFactory.getLogger(BaselineController.class);

    // ── Configurable parameters ─────────────────────────────────────────

    private final double sloLatencyMs;

    /** Fraction of SLO below which we consider the system "comfortable". */
    private final double comfortFactor;

    /** Additive increase step for concurrency. */
    private final int concurrencyIncreaseStep;

    /** Multiplicative decrease factor for concurrency (e.g. 0.85). */
    private final double concurrencyDecreaseFactor;

    /** Error rate threshold above which we consider errors "significant". */
    private final double errorRateThreshold;

    /** Timeout rate threshold above which we trigger decrease. */
    private final double timeoutRateThreshold;

    /** Additive step for Hikari pool size changes (both up and down). */
    private final int hikariStep;

    /** Pending connections threshold to trigger pool increase. */
    private final double hikariPendingThreshold;

    /** Acquire time p95 (ms) threshold to trigger pool increase. */
    private final double hikariAcquireTimeThresholdMs;

    // ── State for trend detection ───────────────────────────────────────

    private double prevLatencyP95 = Double.NaN;
    private double prevErrorRate = Double.NaN;
    private double prevTimeoutRate = Double.NaN;
    private double prevAcquireTimeP95 = Double.NaN;
    private int ticksWithoutPending = 0;

    // ── Constructors ────────────────────────────────────────────────────

    /**
     * Full constructor.
     */
    public BaselineController(double sloLatencyMs,
                              double comfortFactor,
                              int concurrencyIncreaseStep,
                              double concurrencyDecreaseFactor,
                              double errorRateThreshold,
                              double timeoutRateThreshold,
                              int hikariStep,
                              double hikariPendingThreshold,
                              double hikariAcquireTimeThresholdMs) {
        this.sloLatencyMs = sloLatencyMs;
        this.comfortFactor = comfortFactor;
        this.concurrencyIncreaseStep = concurrencyIncreaseStep;
        this.concurrencyDecreaseFactor = concurrencyDecreaseFactor;
        this.errorRateThreshold = errorRateThreshold;
        this.timeoutRateThreshold = timeoutRateThreshold;
        this.hikariStep = hikariStep;
        this.hikariPendingThreshold = hikariPendingThreshold;
        this.hikariAcquireTimeThresholdMs = hikariAcquireTimeThresholdMs;
    }

    /**
     * Constructor with reasonable defaults.
     *
     * @param sloLatencyMs target SLO latency p95 in ms
     */
    public BaselineController(double sloLatencyMs) {
        this(sloLatencyMs,
                0.7,    // comfortFactor
                5,      // concurrencyIncreaseStep
                0.85,   // concurrencyDecreaseFactor
                0.01,   // errorRateThreshold (1%)
                0.005,  // timeoutRateThreshold (0.5%)
                1,      // hikariStep
                0.0,    // hikariPendingThreshold (any pending triggers)
                10.0    // hikariAcquireTimeThresholdMs
        );
    }

    // ── Controller interface ─────────────────────────────────────────────

    @Override
    public ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators) {
        ControlAction.Builder action = ControlAction.builder();

        double latencyP95 = snapshot.get(MetricNames.LATENCY_P95_MS, 0.0);
        double errorRate = snapshot.get(MetricNames.ERROR_RATE, 0.0);
        double timeoutRate = snapshot.get(MetricNames.TIMEOUT_RATE, 0.0);

        // ── Concurrency decision ─────────────────────────────────────────
        Actuator concurrencyActuator = findActuator(actuators, ConcurrencyLimiter.ACTUATOR_NAME);
        if (concurrencyActuator != null) {
            int currentLimit = concurrencyActuator.currentValue();
            int newLimit = decideConcurrency(currentLimit, latencyP95, errorRate, timeoutRate);
            if (newLimit != currentLimit) {
                action.set(concurrencyActuator.name(), newLimit);
                log.debug("Baseline: concurrency {} → {} (p95={} slo={} err={} tout={})",
                        currentLimit, newLimit, latencyP95, sloLatencyMs, errorRate, timeoutRate);
            }
        }

        // ── Hikari pool decision ─────────────────────────────────────────
        Actuator hikariActuator = findActuator(actuators, HikariPoolActuator.ACTUATOR_NAME);
        if (hikariActuator != null) {
            int currentPool = hikariActuator.currentValue();
            int newPool = decideHikariPool(currentPool, snapshot, latencyP95);
            if (newPool != currentPool) {
                action.set(hikariActuator.name(), newPool);
                log.debug("Baseline: hikari pool {} → {} (pending={} acquire_p95={})",
                        currentPool, newPool,
                        snapshot.get(MetricNames.HIKARI_PENDING, 0.0),
                        snapshot.get(MetricNames.HIKARI_ACQUIRE_P95_MS, 0.0));
            }
        }

        // Track trends for next iteration
        prevLatencyP95 = latencyP95;
        prevErrorRate = errorRate;
        prevTimeoutRate = timeoutRate;
        prevAcquireTimeP95 = snapshot.get(MetricNames.HIKARI_ACQUIRE_P95_MS, 0.0);

        return action.build();
    }

    // ── Concurrency logic ────────────────────────────────────────────────

    private int decideConcurrency(int current, double latencyP95, double errorRate, double timeoutRate) {
        boolean sloViolated = latencyP95 > sloLatencyMs;
        boolean comfortable = latencyP95 < (sloLatencyMs * comfortFactor);
        boolean errorsRising = errorRate > errorRateThreshold
                || (!Double.isNaN(prevErrorRate) && errorRate > prevErrorRate * 1.5 && errorRate > 0.001);
        boolean timeoutsRising = timeoutRate > timeoutRateThreshold
                || (!Double.isNaN(prevTimeoutRate) && timeoutRate > prevTimeoutRate * 1.5 && timeoutRate > 0.001);

        // ── Decrease path (multiplicative) ──
        if (sloViolated || timeoutsRising || errorsRising) {
            int decreased = (int) Math.floor(current * concurrencyDecreaseFactor);
            return Math.max(decreased, 1); // never go below 1
        }

        // ── Increase path (additive) ──
        if (comfortable && errorRate <= errorRateThreshold && timeoutRate <= timeoutRateThreshold) {
            return current + concurrencyIncreaseStep;
        }

        // ── Hold ──
        return current;
    }

    // ── Hikari pool logic ────────────────────────────────────────────────

    private int decideHikariPool(int currentPool, MetricsSnapshot snapshot, double latencyP95) {
        double pending = snapshot.get(MetricNames.HIKARI_PENDING, 0.0);
        double acquireP95 = snapshot.get(MetricNames.HIKARI_ACQUIRE_P95_MS, 0.0);
        boolean sloViolated = latencyP95 > sloLatencyMs;

        boolean hasPendingDeficit = pending > hikariPendingThreshold;
        boolean acquireTimeGrowing = !Double.isNaN(prevAcquireTimeP95)
                && acquireP95 > prevAcquireTimeP95 * 1.2
                && acquireP95 > hikariAcquireTimeThresholdMs;

        // Track ticks without pending
        if (pending <= hikariPendingThreshold) {
            ticksWithoutPending++;
        } else {
            ticksWithoutPending = 0;
        }

        // ── Increase: deficit detected AND not in SLO violation zone ──
        if (hasPendingDeficit && acquireTimeGrowing && !sloViolated) {
            return currentPool + hikariStep;
        }

        // ── Decrease: no deficit for a sustained period (e.g. 10+ ticks) ──
        if (ticksWithoutPending >= 10 && currentPool > 1) {
            ticksWithoutPending = 0; // reset after action
            return currentPool - hikariStep;
        }

        // ── Decrease: latency degradation after pool increase ──
        if (sloViolated && !Double.isNaN(prevLatencyP95) && latencyP95 > prevLatencyP95 * 1.1) {
            // Possible DB overload — conservative decrease
            return currentPool - hikariStep;
        }

        // ── Hold ──
        return currentPool;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Actuator findActuator(List<Actuator> actuators, String name) {
        for (Actuator a : actuators) {
            if (name.equals(a.name())) return a;
        }
        return null;
    }
}
