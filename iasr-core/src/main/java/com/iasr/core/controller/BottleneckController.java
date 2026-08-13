package com.iasr.core.controller;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.actuator.HikariPoolActuator;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Controller that decides <b>which</b> resource to move before deciding how far.
 *
 * <h3>What it adds over independent rules</h3>
 * {@link BaselineController} runs one rule per actuator, coupled only through a
 * shared SLO. Each rule sees "latency is high" and reacts in its own direction,
 * which leaves the loop unable to distinguish two opposite situations that look
 * identical from a single latency number:
 * <ul>
 *   <li>latency is high <em>because the limiter is throttling</em> — requests
 *       are queued for permits while the dependency is idle, and the fix is to
 *       admit <em>more</em>;</li>
 *   <li>latency is high <em>because the dependency is saturated</em> — and the
 *       fix is to admit less.</li>
 * </ul>
 * Threshold rules on total latency answer both the same way. This controller
 * separates them by asking where the time actually went.
 *
 * <h3>The decomposition</h3>
 * A served request's time divides into three observable terms:
 * <pre>
 *   W = W_admission + W_pool + W_dependency
 * </pre>
 * The bottleneck is not the largest term — {@code W_dependency} is almost
 * always largest, because it is the actual work — but the term furthest above
 * its <b>irreducible</b> cost:
 * <pre>
 *   excess_admission = W_admission            (queuing for a permit is pure delay)
 *   excess_pool      = W_pool                 (waiting for a connection is pure delay)
 *   excess_dependency = W_dependency − min(W_dependency)
 * </pre>
 * The dependency's irreducible cost is estimated as the smallest service time
 * observed, re-probed periodically so it can rise when the dependency itself
 * slows down. Anchoring on the system's own behaviour rather than on an
 * external target matters: a target the system cannot reach makes SLO-anchored
 * rules shed without limit trying, which is measurable in this project's own
 * sensitivity data.
 *
 * <h3>Routing</h3>
 * Action goes to the resource with the largest excess:
 * <ul>
 *   <li><b>dependency congested</b> — checked first, because the dependency is
 *       terminal. Once it is saturated, every upstream queue is only a choice
 *       of where requests wait, and growing anything moves the queue into the
 *       place where crowding costs throughput too;</li>
 *   <li><b>pool</b> — connections are the constraint, grow the pool;</li>
 *   <li><b>admission</b> — the limit is the constraint, raise it, even while the
 *       SLO is violated, because the queue <em>is</em> the violation;</li>
 *   <li><b>dependency</b> — nothing here can be grown, so shed: reduce the
 *       limit proportionally to how far latency sits past the target.</li>
 * </ul>
 * A dominant term below {@link #MIN_EXCESS_SHARE} of the total is treated as
 * noise and nothing moves — with three near-equal terms there is no bottleneck
 * to speak of, and acting on the largest of three coin flips is worse than
 * waiting a tick.
 */
@Slf4j
public class BottleneckController implements Controller {

    /**
     * Share of the <b>avoidable</b> delay the dominant term must reach before
     * acting.
     * <p>
     * Normalised against the sum of excesses, not against total latency.
     * Against the total it would almost never fire: {@code W_dependency} is the
     * actual work and dwarfs every excess, so a threshold measured against it
     * says "no bottleneck" even when one term accounts for all of the avoidable
     * delay. Measured that way the controller sat idle on more than half its
     * ticks and never touched the limiter at all.
     */
    private static final double MIN_EXCESS_SHARE = 0.5;

    /**
     * Avoidable delay below which nothing is worth doing, as a fraction of the
     * SLO. Keeps sub-millisecond jitter from being read as a bottleneck.
     */
    private static final double NOISE_FLOOR_OF_SLO = 0.02;

    /** Below this throughput the system is treated as idle. */
    private static final double MIN_ACTIVE_RPS = 1.0;

    /** Ticks between resets of the dependency's baseline estimate. */
    private static final int PROBE_INTERVAL_TICKS = 30;

    /**
     * How far above its baseline the dependency must be before shedding is
     * worth doing.
     * <p>
     * Shedding buys latency back only by clearing a queue. When the dependency
     * is already serving at its baseline speed, the remaining latency is
     * irreducible and admitting less trades throughput for nothing. Without
     * this check a target the dependency cannot meet keeps the ratio just under
     * one, and geometric shrinking compounds a few percent per tick into a
     * collapsed limit — slower than an SLO-anchored gradient, but the same
     * ending.
     */
    private static final double CONGESTION_TOLERANCE = 0.1;

    private final double sloLatencyMs;
    private final int concurrencyStep;
    private final int hikariStep;
    private final double minGradient;

    private double dependencyBaselineMs = Double.NaN;
    private int ticksUntilProbe = PROBE_INTERVAL_TICKS;

    public BottleneckController(double sloLatencyMs) {
        this(sloLatencyMs, 5, 1, 0.9);
    }

    public BottleneckController(double sloLatencyMs, int concurrencyStep,
                                int hikariStep, double minGradient) {
        this.sloLatencyMs = sloLatencyMs;
        this.concurrencyStep = concurrencyStep;
        this.hikariStep = hikariStep;
        this.minGradient = minGradient;
    }

    @Override
    public ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators) {
        ControlAction.Builder action = ControlAction.builder();

        Actuator limiter = find(actuators, ConcurrencyLimiter.ACTUATOR_NAME);
        Actuator pool = find(actuators, HikariPoolActuator.ACTUATOR_NAME);

        double goodput = snapshot.get(MetricNames.GOODPUT_RPS, 0.0);
        double latencyP95 = snapshot.get(MetricNames.LATENCY_P95_MS, 0.0);
        double wAdmission = snapshot.get(MetricNames.ADMISSION_WAIT_MEAN_MS, 0.0);
        double wPool = snapshot.get(MetricNames.HIKARI_ACQUIRE_MEAN_MS, 0.0);
        double wDependency = snapshot.get(MetricNames.DEPENDENCY_MEAN_MS, 0.0);

        if (goodput < MIN_ACTIVE_RPS || wDependency <= 0) {
            hold(action, limiter, "idle");
            hold(action, pool, "idle");
            return action.build();
        }

        double baseline = updateDependencyBaseline(wDependency);
        double excessAdmission = Math.max(0, wAdmission);
        double excessPool = Math.max(0, wPool);
        double excessDependency = Math.max(0, wDependency - baseline);
        double totalExcess = excessAdmission + excessPool + excessDependency;
        double dominant = Math.max(excessAdmission, Math.max(excessPool, excessDependency));

        if (totalExcess < NOISE_FLOOR_OF_SLO * sloLatencyMs) {
            hold(action, limiter, "no_avoidable_delay");
            hold(action, pool, "no_avoidable_delay");
            return action.build();
        }
        if (dominant < MIN_EXCESS_SHARE * totalExcess) {
            hold(action, limiter, "no_dominant_bottleneck");
            hold(action, pool, "no_dominant_bottleneck");
            return action.build();
        }

        log.debug("Bottleneck: adm={} pool={} dep={} (baseline={})",
                excessAdmission, excessPool, excessDependency, baseline);

        // The dependency is terminal: nothing upstream makes it faster, so once
        // it is congested every other queue is only a choice of where requests
        // wait.  Growing the pool then moves the queue out of HikariCP and into
        // the dependency, where crowding costs throughput as well as latency —
        // which is exactly what the traces showed, dependency time climbing
        // 36 → 82 ms while the pool grew and the connection wait stayed pinned.
        boolean dependencyCongested = wDependency > baseline * (1 + CONGESTION_TOLERANCE);

        if (dependencyCongested) {
            shed(action, limiter, latencyP95, wDependency, baseline);
            hold(action, pool, "bottleneck_dependency_saturated");
        } else if (dominant == excessPool) {
            grow(action, pool, hikariStep, "bottleneck_pool");
            hold(action, limiter, "bottleneck_pool");
        } else if (dominant == excessAdmission) {
            // The queue for permits is itself the delay: admitting more shortens
            // total latency.  A rule reading total latency alone would shed here
            // and make the very number it is reacting to worse.
            grow(action, limiter, concurrencyStep, "bottleneck_admission");
            hold(action, pool, "bottleneck_admission");
        } else {
            shed(action, limiter, latencyP95, wDependency, baseline);
            hold(action, pool, "bottleneck_dependency");
        }

        return action.build();
    }

    /**
     * Smallest dependency service time seen recently, re-probed so the estimate
     * can rise when the dependency itself degrades — otherwise a one-off fast
     * window would peg the baseline forever and every later tick would look
     * like congestion.
     */
    private double updateDependencyBaseline(double wDependency) {
        if (Double.isNaN(dependencyBaselineMs) || --ticksUntilProbe <= 0) {
            dependencyBaselineMs = wDependency;
            ticksUntilProbe = PROBE_INTERVAL_TICKS;
        } else {
            dependencyBaselineMs = Math.min(dependencyBaselineMs, wDependency);
        }
        return dependencyBaselineMs;
    }

    /**
     * Reduce the limit in proportion to the overshoot, taking the gentler of two
     * demands: reach the SLO, or clear the dependency's queue.
     * <p>
     * The {@code max} is what keeps an unreachable target from collapsing the
     * limit. Were it {@code min}, an SLO below what the dependency can deliver
     * would hold the ratio under one forever and the loop would shed until it
     * served almost nothing — the failure this project measured directly.
     */
    private void shed(ControlAction.Builder action, Actuator limiter,
                      double latencyP95, double wDependency, double baseline) {
        if (limiter == null) {
            return;
        }
        if (latencyP95 <= sloLatencyMs) {
            hold(action, limiter, "bottleneck_dependency_within_slo");
            return;
        }
        if (wDependency <= baseline * (1 + CONGESTION_TOLERANCE)) {
            // Latency is over target, but not because anything is queued here.
            // Nothing to clear, so shedding would cost throughput and buy no
            // latency — the target is simply beyond what this dependency does.
            hold(action, limiter, "bottleneck_dependency_irreducible");
            return;
        }
        double towardsSlo = sloLatencyMs / latencyP95;
        double towardsNoQueue = baseline / wDependency;
        double gradient = Math.min(1.0, Math.max(minGradient, Math.max(towardsSlo, towardsNoQueue)));

        int current = limiter.currentValue();
        int reduced = Math.max(1, (int) Math.floor(current * gradient));
        if (reduced != current) {
            action.set(limiter.name(), reduced, "bottleneck_dependency_shed");
        } else {
            hold(action, limiter, "bottleneck_dependency_shed");
        }
    }

    private static void grow(ControlAction.Builder action, Actuator actuator,
                             int step, String reason) {
        if (actuator == null) {
            return;
        }
        int target = actuator.currentValue() + step;
        if (target <= actuator.maxValue()) {
            action.set(actuator.name(), target, reason);
        } else {
            action.hold(actuator.name(), reason + "_at_max");
        }
    }

    private static void hold(ControlAction.Builder action, Actuator actuator, String reason) {
        if (actuator != null) {
            action.hold(actuator.name(), reason);
        }
    }

    private static Actuator find(List<Actuator> actuators, String name) {
        for (Actuator a : actuators) {
            if (name.equals(a.name())) return a;
        }
        return null;
    }
}
