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

    /**
     * Multiplicative decrease factor for concurrency (e.g. 0.85), used when
     * gradient descent is unavailable — for error- and timeout-driven
     * decreases, where there is no latency ratio to compute from.
     */
    private final double concurrencyDecreaseFactor;

    /**
     * Floor on the latency gradient {@code SLO / observed}, bounding how much
     * of the limit a single tick may remove.  {@code 0} disables gradient
     * descent and falls back to {@link #concurrencyDecreaseFactor}.
     * <p>
     * Geometric decay treats a mild overshoot and a tenfold one identically,
     * so recovery time depends on how far the limit happens to be from the
     * right value rather than on how wrong it is: ×0.85 per tick needs about
     * 19 ticks to fall from 190 to 33, longer than many surges last, and the
     * loop finishes converging just as the disturbance ends.  Scaling by
     * {@code SLO / observed} instead makes the correction proportional to the
     * error — latency at three times the target divides the limit by three —
     * and it settles on its own, because the ratio approaches 1 as latency
     * approaches the SLO.  The floor keeps a single bad sample from collapsing
     * the limit outright.
     */
    private final double minGradient;

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
     * Idle connections that must be sitting spare before the pool is
     * considered oversized and eligible to shrink.
     * <p>
     * Shrinking on "nothing queued" alone is wrong in an ordinary way: a pool
     * sized exactly right has no queue and no spare connections, and the rule
     * shrinks it anyway, every ten ticks, until it becomes the bottleneck.
     * Requiring genuinely idle connections keeps the reclaim behaviour for
     * pools that really are too large without eroding correct ones.
     */
    private final double hikariIdleShrinkThreshold;

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
     * SLO turns it into the concurrency the SLO allows.  λ is the
     * <b>offered</b> rate, {@code goodput / (1 - timeoutRate)}, because
     * achieved throughput is already suppressed by whatever the limiter is
     * shedding — sizing the limit from it means sizing it from a number the
     * limit itself produced.
     * <p>
     * The correction is necessary but not sufficient: the rule is anchored to
     * an externally chosen SLO, and that makes it regime-dependent. Where the
     * target sits comfortably above what the dependency can deliver (150 ms
     * against 55 ms of service time) it cut p95 by 37-44% at no cost. Where the
     * target approaches the achievable minimum (60 ms against 53 ms) the only
     * way to satisfy it is to stop serving, and it shed 10% of traffic that
     * nothing required. Under heavy overload it gave up 22-49% of throughput
     * for roughly half the tail.
     * <p>
     * A loop that measured queueing against the system's own best observed
     * latency, rather than against a number chosen in advance, would not have
     * this failure mode — see the Gradient baseline in the benchmark module.
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
        this(sloLatencyMs, comfortFactor, concurrencyIncreaseStep, concurrencyDecreaseFactor,
                errorRateThreshold, timeoutRateThreshold, hikariStep, hikariPendingThreshold,
                hikariAcquireTimeThresholdMs, utilizationGate, sloTargetHeadroom, 0.0, 0.0);
    }

    /**
     * Canonical constructor.
     * <p>
     * The parameter list has outgrown what positional arguments carry safely;
     * a builder is the right shape for the next breaking release.
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
                              double sloTargetHeadroom,
                              double minGradient,
                              double hikariIdleShrinkThreshold) {
        this.hikariIdleShrinkThreshold = hikariIdleShrinkThreshold;
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
        this.minGradient = minGradient;
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
                0.0,    // sloTargetHeadroom — regime-dependent, see the javadoc
                0.9,    // minGradient — see the javadoc for why gentle beats aggressive
                2.0     // hikariIdleShrinkThreshold — spare connections before shrinking
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
            double queueLength = snapshot.get(MetricNames.QUEUE_LENGTH, 0.0);
            Decision decision = decideConcurrency(currentLimit, latencyP95, goodputRps,
                    errorRate, timeoutRate, inflight, queueLength, idleShrink);
            if (decision.value() != currentLimit) {
                action.set(concurrencyActuator.name(), decision.value(), decision.reason());
                log.debug("Baseline: concurrency {} → {} [{}] (p95={} slo={} err={} tout={})",
                        currentLimit, decision.value(), decision.reason(),
                        latencyP95, sloLatencyMs, errorRate, timeoutRate);
            } else {
                action.hold(concurrencyActuator.name(), decision.reason());
            }
        }

        // ── Hikari pool decision ─────────────────────────────────────────
        Actuator hikariActuator = findActuator(actuators, HikariPoolActuator.ACTUATOR_NAME);
        if (hikariActuator != null) {
            int currentPool = hikariActuator.currentValue();
            Decision decision = decideHikariPool(currentPool, snapshot, latencyP95, goodputRps, idleShrink);
            if (decision.value() != currentPool) {
                action.set(hikariActuator.name(), decision.value(), decision.reason());
                log.debug("Baseline: hikari pool {} → {} [{}] (pending={} acquire_p95={})",
                        currentPool, decision.value(), decision.reason(),
                        snapshot.get(MetricNames.HIKARI_PENDING, 0.0),
                        snapshot.get(MetricNames.HIKARI_ACQUIRE_P95_MS, 0.0));
            } else {
                action.hold(hikariActuator.name(), decision.reason());
            }
        }

        // Track trends for next iteration
        prevLatencyP95 = latencyP95;
        prevErrorRate = errorRate;
        prevTimeoutRate = timeoutRate;

        return action.build();
    }

    // ── Concurrency logic ────────────────────────────────────────────────

    private Decision decideConcurrency(int current, double latencyP95, double goodputRps,
                                       double errorRate, double timeoutRate, double inflight,
                                       double queueLength, boolean idleShrink) {
        // Below active-traffic threshold: short idle → hold, sustained idle → gradually shrink
        if (goodputRps < MIN_ACTIVE_RPS && errorRate <= 0 && timeoutRate <= 0) {
            if (idleShrink) {
                return new Decision(Math.max(1, current - concurrencyIncreaseStep), "idle_shrink");
            }
            return new Decision(current, "idle_hold");
        }

        boolean sloViolated = latencyP95 > sloLatencyMs;
        boolean comfortable = latencyP95 > 0 && latencyP95 < (sloLatencyMs * comfortFactor);
        boolean errorsRising = errorRate > errorRateThreshold
                || (!Double.isNaN(prevErrorRate) && errorRate > prevErrorRate * 1.5 && errorRate > 0.001);
        boolean timeoutsRising = timeoutRate > timeoutRateThreshold
                || (!Double.isNaN(prevTimeoutRate) && timeoutRate > prevTimeoutRate * 1.5 && timeoutRate > 0.001);

        int sloTarget = concurrencyTargetFor(goodputRps, timeoutRate);

        // ── Decrease path ──
        if (sloViolated || timeoutsRising || errorsRising) {
            int decreased;
            String reason;
            if (sloViolated && minGradient > 0 && latencyP95 > 0) {
                // Correction proportional to the error, not a fixed fraction.
                double gradient = Math.min(1.0, Math.max(minGradient, sloLatencyMs / latencyP95));
                decreased = (int) Math.floor(current * gradient);
                reason = "decrease_slo_gradient";
            } else if (sloViolated) {
                decreased = (int) Math.floor(current * concurrencyDecreaseFactor);
                reason = "decrease_slo_factor";
            } else {
                decreased = (int) Math.floor(current * concurrencyDecreaseFactor);
                reason = errorsRising ? "decrease_errors" : "decrease_timeouts";
            }
            if (sloViolated && sloTarget > 0 && sloTarget < decreased) {
                decreased = sloTarget;
                reason = "decrease_slo_target";
            }
            return new Decision(Math.max(decreased, 1), reason);
        }

        // ── Increase path (additive, demand-gated and capped) ──
        if (comfortable && errorRate <= errorRateThreshold && timeoutRate <= timeoutRateThreshold) {
            // Only grow the limit when it is actually the binding constraint.
            // Idle headroom is not a reason to add more.
            if (!limitIsBinding(current, inflight, queueLength)) {
                return new Decision(current, "hold_limit_not_binding");
            }
            int increased = current + concurrencyIncreaseStep;
            if (sloTarget > 0 && increased > sloTarget) {
                return new Decision(current, "hold_at_slo_target");
            }
            return new Decision(increased, "increase_headroom");
        }

        // ── Hold ──
        return new Decision(current, "hold_in_band");
    }

    /**
     * Whether the concurrency limit is what is currently holding requests back.
     * <p>
     * A non-zero permit queue settles the question outright: somebody is
     * blocked on the limit right now.  Utilisation is the fallback for when the
     * queue is unavailable or was sampled between bursts — it only suggests
     * saturation, and it needs a threshold that has to be guessed, which is why
     * it is second in line rather than first.
     */
    private boolean limitIsBinding(int current, double inflight, double queueLength) {
        if (queueLength > 0) {
            return true;
        }
        if (utilizationGate <= 0) {
            return true;
        }
        return inflight >= utilizationGate * current;
    }

    /** A chosen value together with the rule that chose it. */
    private record Decision(int value, String reason) {}

    /**
     * Concurrency that Little's law says is needed to serve the observed
     * throughput within the SLO: {@code L = λ × W}.
     *
     * @return the target, or {@code -1} when it cannot be estimated — either
     *         the feature is off or there is too little traffic for the
     *         throughput reading to mean anything
     */
    private int concurrencyTargetFor(double goodputRps, double timeoutRate) {
        if (sloTargetHeadroom <= 0) {
            return -1;
        }
        // λ must be the rate arriving at the door, not the rate that got
        // through it.  Goodput alone is already suppressed by whatever the
        // limiter is shedding, so sizing the limit from it means sizing it from
        // a number the limit itself produced — the estimate then settles lower
        // than the system can actually sustain.
        double offeredRps = timeoutRate > 0 && timeoutRate < 1.0
                ? goodputRps / (1.0 - timeoutRate)
                : goodputRps;
        if (offeredRps < MIN_ACTIVE_RPS) {
            return -1;
        }
        double target = sloTargetHeadroom * offeredRps * (sloLatencyMs / 1000.0);
        return (int) Math.max(1, Math.ceil(target));
    }

    // ── Hikari pool logic ────────────────────────────────────────────────

    private Decision decideHikariPool(int currentPool, MetricsSnapshot snapshot,
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

        // Count stretches where the pool is demonstrably oversized, not merely
        // stretches where nothing is queued.  An empty queue is the normal
        // healthy state, so shrinking on that alone hands back headroom during
        // exactly the calm periods that precede a surge.  Spare connections
        // sitting idle are the honest evidence of over-provisioning.
        double idle = snapshot.get(MetricNames.HIKARI_IDLE, Double.NaN);
        boolean poolLooksOversized = pending <= hikariPendingThreshold
                && (Double.isNaN(idle) || idle >= hikariIdleShrinkThreshold);
        if (goodputRps >= MIN_ACTIVE_RPS) {
            if (poolLooksOversized) {
                ticksWithoutPending++;
            } else {
                ticksWithoutPending = 0;
            }
        }

        // Below active-traffic threshold: short idle → hold, sustained idle → gradually shrink
        if (goodputRps < MIN_ACTIVE_RPS && pending <= 0) {
            if (idleShrink) {
                return new Decision(Math.max(1, currentPool - hikariStep), "idle_shrink");
            }
            return new Decision(currentPool, "idle_hold");
        }

        // ── Increase: clear connection deficit detected ──
        // Pending threads + high acquire time = pool is the bottleneck.
        // Allow increase even during SLO violation when the deficit is the
        // likely cause (pending > 0 means requests are queued for connections).
        if (hasPendingDeficit && acquireTimeHigh) {
            return new Decision(currentPool + hikariStep, "increase_connection_deficit");
        }

        // ── Decrease: spare connections for a sustained period under active traffic ──
        if (ticksWithoutPending >= 10 && currentPool > 1) {
            ticksWithoutPending = 0;
            return new Decision(currentPool - hikariStep, "decrease_idle_connections");
        }

        // ── Decrease: latency degradation after pool increase ──
        // Only fires if the pool was actually increased on the previous tick.
        // Without this guard, any idle→load transition (latency spike)
        // would falsely trigger a pool decrease.
        if (poolWasIncreased && sloViolated
                && !Double.isNaN(prevLatencyP95) && latencyP95 > prevLatencyP95 * 1.1) {
            return new Decision(Math.max(1, currentPool - hikariStep), "rollback_after_increase");
        }

        // ── Hold ──
        return new Decision(currentPool, "hold");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Actuator findActuator(List<Actuator> actuators, String name) {
        for (Actuator a : actuators) {
            if (name.equals(a.name())) return a;
        }
        return null;
    }
}
