package com.iasr.core.controller;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class BaselineController implements Controller {

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

    /**
     * Fraction of the current limit that must actually be in use before the
     * limit is allowed to grow.  {@code 0} disables the gate.
     * <p>
     * Without this, additive increase has no notion of "enough": it raises the
     * limit on every tick where latency is comfortable, whether or not anything
     * is waiting for a permit.  A service running healthily at 7 concurrent
     * requests will ratchet its limit to the configured ceiling and arrive at
     * the next traffic surge with no admission control left — the limit is
     * highest exactly when protection is needed most.
     */
    private final double utilizationGate;

    /**
     * Multiplier applied to the Little's-law concurrency estimate before using
     * it as a growth ceiling and as a decrease target.  <b>Off by default.</b>
     * <p>
     * Little's law gives {@code L = λ × W}: to serve λ requests per second with
     * latency W, exactly {@code λ × W} of them are in flight.  Setting W to the
     * SLO turns it into the concurrency that meets the SLO at the throughput
     * being achieved.
     * <p>
     * Measured on the benchmark, this reliably improves the latency tail
     * (p95 by 4–13% on top of the demand gate) and reliably costs throughput
     * (goodput down 7–14%, rejections up 4–12 points under a degraded
     * dependency).  The bias is structural rather than incidental: λ is the
     * <em>achieved</em> throughput, which is already suppressed by whatever the
     * limiter is shedding, so under overload the target is computed from a rate
     * that reflects the shedding it is meant to size — and it settles lower
     * than it should.  Correcting λ for the rejected share would be the way to
     * fix that; until then this is a knob for latency-sensitive services, not a
     * default.
     */
    private final double sloTargetHeadroom;

    /**
     * Minimum goodput (RPS) to consider the system "actively serving traffic".
     * Below this threshold the system is treated as idle regardless of
     * health-check / actuator / monitoring endpoints generating low-volume
     * background traffic.
     */
    private static final double MIN_ACTIVE_RPS = 1.0;

    /** Ticks of consecutive idle (goodput < minActiveRps) before starting to shrink resources. */
    private static final int IDLE_SHRINK_THRESHOLD = 24;

    // ── State for trend detection ───────────────────────────────────────

    private double prevLatencyP95 = Double.NaN;
    private double prevErrorRate = Double.NaN;
    private double prevTimeoutRate = Double.NaN;
    private int ticksWithoutPending = 0;
    private int consecutiveIdleTicks = 0;
    private int prevHikariPool = -1;

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
                              double hikariAcquireTimeThresholdMs,
                              double utilizationGate,
                              double sloTargetHeadroom) {
        this.sloLatencyMs = sloLatencyMs;
        this.comfortFactor = comfortFactor;
        this.concurrencyIncreaseStep = concurrencyIncreaseStep;
        this.concurrencyDecreaseFactor = concurrencyDecreaseFactor;
        this.errorRateThreshold = errorRateThreshold;
        this.timeoutRateThreshold = timeoutRateThreshold;
        this.hikariStep = hikariStep;
        this.hikariPendingThreshold = hikariPendingThreshold;
        this.hikariAcquireTimeThresholdMs = hikariAcquireTimeThresholdMs;
        this.utilizationGate = utilizationGate;
        this.sloTargetHeadroom = sloTargetHeadroom;
    }

    /**
     * @deprecated use the constructor that also takes {@code utilizationGate}
     *             and {@code sloTargetHeadroom}.  This overload keeps the
     *             pre-1.0.14 behaviour of growing the limit regardless of
     *             demand, which drifts the limit to its ceiling under healthy
     *             traffic.
     */
    @Deprecated(since = "1.0.14")
    public BaselineController(double sloLatencyMs,
                              double comfortFactor,
                              int concurrencyIncreaseStep,
                              double concurrencyDecreaseFactor,
                              double errorRateThreshold,
                              double timeoutRateThreshold,
                              int hikariStep,
                              double hikariPendingThreshold,
                              double hikariAcquireTimeThresholdMs) {
        this(sloLatencyMs, comfortFactor, concurrencyIncreaseStep, concurrencyDecreaseFactor,
                errorRateThreshold, timeoutRateThreshold, hikariStep, hikariPendingThreshold,
                hikariAcquireTimeThresholdMs, 0.0, 0.0);
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
                10.0,   // hikariAcquireTimeThresholdMs
                0.9,    // utilizationGate — grow only when the limit is binding
                0.0     // sloTargetHeadroom — off by default, see the javadoc
        );
    }

    // ── Controller interface ─────────────────────────────────────────────

    @Override
    public ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators) {
        ControlAction.Builder action = ControlAction.builder();

        double latencyP95 = snapshot.get(MetricNames.LATENCY_P95_MS, 0.0);
        double goodputRps = snapshot.get(MetricNames.GOODPUT_RPS, 0.0);
        double errorRate = snapshot.get(MetricNames.ERROR_RATE, 0.0);
        double timeoutRate = snapshot.get(MetricNames.TIMEOUT_RATE, 0.0);

        boolean idle = goodputRps < MIN_ACTIVE_RPS && errorRate <= 0 && timeoutRate <= 0;
        if (idle) {
            consecutiveIdleTicks++;
        } else {
            consecutiveIdleTicks = 0;
        }
        boolean idleShrink = consecutiveIdleTicks >= IDLE_SHRINK_THRESHOLD;

        // ── Concurrency decision ─────────────────────────────────────────
        Actuator concurrencyActuator = findActuator(actuators, ConcurrencyLimiter.ACTUATOR_NAME);
        if (concurrencyActuator != null) {
            int currentLimit = concurrencyActuator.currentValue();
            double inflight = snapshot.get(MetricNames.INFLIGHT, 0.0);
            int newLimit = decideConcurrency(currentLimit, latencyP95, goodputRps,
                    errorRate, timeoutRate, inflight, idleShrink);
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
            int newPool = decideHikariPool(currentPool, snapshot, latencyP95, goodputRps, idleShrink);
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

        return action.build();
    }

    // ── Concurrency logic ────────────────────────────────────────────────

    private int decideConcurrency(int current, double latencyP95, double goodputRps,
                                   double errorRate, double timeoutRate, double inflight,
                                   boolean idleShrink) {
        // Below active-traffic threshold: short idle → hold, sustained idle → gradually shrink
        if (goodputRps < MIN_ACTIVE_RPS && errorRate <= 0 && timeoutRate <= 0) {
            if (idleShrink) {
                return Math.max(1, current - concurrencyIncreaseStep);
            }
            return current;
        }

        boolean sloViolated = latencyP95 > sloLatencyMs;
        boolean comfortable = latencyP95 > 0 && latencyP95 < (sloLatencyMs * comfortFactor);
        boolean errorsRising = errorRate > errorRateThreshold
                || (!Double.isNaN(prevErrorRate) && errorRate > prevErrorRate * 1.5 && errorRate > 0.001);
        boolean timeoutsRising = timeoutRate > timeoutRateThreshold
                || (!Double.isNaN(prevTimeoutRate) && timeoutRate > prevTimeoutRate * 1.5 && timeoutRate > 0.001);

        int sloTarget = concurrencyTargetFor(goodputRps);

        // ── Decrease path (multiplicative, floored at the SLO target) ──
        if (sloViolated || timeoutsRising || errorsRising) {
            int decreased = (int) Math.floor(current * concurrencyDecreaseFactor);
            // Geometric decay alone needs ~19 ticks to fall from 190 to 33, which
            // is longer than many surges last — the loop finishes converging just
            // as the disturbance ends.  When Little's law can name the concurrency
            // that meets the SLO at the throughput being achieved, descend to it
            // directly instead of feeling the way down.
            if (sloViolated && sloTarget > 0) {
                decreased = Math.min(decreased, sloTarget);
            }
            return Math.max(decreased, 1);
        }

        // ── Increase path (additive, demand-gated and capped) ──
        if (comfortable && errorRate <= errorRateThreshold && timeoutRate <= timeoutRateThreshold) {
            // Only grow the limit when it is actually the binding constraint.
            // Idle headroom is not a reason to add more.
            if (utilizationGate > 0 && inflight < utilizationGate * current) {
                return current;
            }
            int increased = current + concurrencyIncreaseStep;
            if (sloTarget > 0 && increased > sloTarget) {
                return current;
            }
            return increased;
        }

        // ── Hold ──
        return current;
    }

    /**
     * Concurrency that Little's law says is needed to serve the observed
     * throughput within the SLO: {@code L = λ × W}.
     *
     * @return the target, or {@code -1} when it cannot be estimated — either
     *         the feature is off or there is too little traffic for the
     *         throughput reading to mean anything
     */
    private int concurrencyTargetFor(double goodputRps) {
        if (sloTargetHeadroom <= 0 || goodputRps < MIN_ACTIVE_RPS) {
            return -1;
        }
        double target = sloTargetHeadroom * goodputRps * (sloLatencyMs / 1000.0);
        return (int) Math.max(1, Math.ceil(target));
    }

    // ── Hikari pool logic ────────────────────────────────────────────────

    private int decideHikariPool(int currentPool, MetricsSnapshot snapshot,
                                  double latencyP95, double goodputRps, boolean idleShrink) {
        double pending = snapshot.get(MetricNames.HIKARI_PENDING, 0.0);
        double acquireP95 = snapshot.get(MetricNames.HIKARI_ACQUIRE_P95_MS, 0.0);
        boolean sloViolated = latencyP95 > sloLatencyMs;

        // Detect "the pool grew since the previous tick" and record the current
        // value up front, before any of the early returns below.  Updating this
        // field only on the fall-through path would leave it pinned to an
        // arbitrarily old tick, making the degradation check compare against
        // stale history.
        boolean poolWasIncreased = prevHikariPool >= 0 && currentPool > prevHikariPool;
        prevHikariPool = currentPool;

        boolean hasPendingDeficit = pending > hikariPendingThreshold;
        boolean acquireTimeHigh = acquireP95 > hikariAcquireTimeThresholdMs;

        // Track ticks without pending only under real traffic.
        if (goodputRps >= MIN_ACTIVE_RPS) {
            if (pending <= hikariPendingThreshold) {
                ticksWithoutPending++;
            } else {
                ticksWithoutPending = 0;
            }
        }

        // Below active-traffic threshold: short idle → hold, sustained idle → gradually shrink
        if (goodputRps < MIN_ACTIVE_RPS && pending <= 0) {
            if (idleShrink) {
                return Math.max(1, currentPool - hikariStep);
            }
            return currentPool;
        }

        // ── Increase: clear connection deficit detected ──
        // Pending threads + high acquire time = pool is the bottleneck.
        // Allow increase even during SLO violation when the deficit is the
        // likely cause (pending > 0 means requests are queued for connections).
        if (hasPendingDeficit && acquireTimeHigh) {
            return currentPool + hikariStep;
        }

        // ── Decrease: no deficit for a sustained period under active traffic ──
        if (ticksWithoutPending >= 10 && currentPool > 1) {
            ticksWithoutPending = 0;
            return currentPool - hikariStep;
        }

        // ── Decrease: latency degradation after pool increase ──
        // Only fires if the pool was actually increased on the previous tick.
        // Without this guard, any idle→load transition (latency spike)
        // would falsely trigger a pool decrease.
        if (poolWasIncreased && sloViolated
                && !Double.isNaN(prevLatencyP95) && latencyP95 > prevLatencyP95 * 1.1) {
            return Math.max(1, currentPool - hikariStep);
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
