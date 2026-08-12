package com.iasr.benchmark.baseline;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.actuator.ConcurrencyLimiter;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Comparison baseline: adaptive concurrency limiting in the style of Netflix's
 * {@code concurrency-limits} Gradient algorithm.
 *
 * <h3>Why this exists</h3>
 * Measuring IASR only against a static limit compares it to the absence of
 * adaptation, which is not the state of the art. Adaptive concurrency limiting
 * is a solved problem with well-known implementations — Netflix's Gradient and
 * Vegas, Envoy's adaptive concurrency filter — and a result that beats a fixed
 * semaphore says little about whether this control loop is worth preferring to
 * them. This baseline makes the honest comparison possible.
 *
 * <h3>The algorithm</h3>
 * The limit is scaled by the ratio between the best latency the system has been
 * seen to achieve and the latency it is achieving now:
 * <pre>
 *   gradient  = clamp(rttNoLoad / rttActual, minGradient, 1.0)
 *   newLimit  = limit × gradient + allowance
 *   limit     = limit × (1 - smoothing) + newLimit × smoothing
 * </pre>
 * The reference point is the crucial difference from IASR's own gradient rule:
 * this one measures queueing against the system's <em>own</em> best case rather
 * than against an externally chosen SLO. A loop told to hit a target it cannot
 * reach will shed traffic without limit trying; a loop looking for the point
 * where added concurrency stops buying throughput converges on the knee of the
 * throughput curve instead. The {@code allowance} term keeps the limit from
 * collapsing when the gradient stays below one.
 *
 * <h3>Where this departs from the original</h3>
 * Faithful in mechanism, not a port. The differences follow from running inside
 * IASR's loop rather than per request:
 * <ul>
 *   <li>Latency arrives as a per-window p95 once per control tick, not as an
 *       RTT sample per request, so the limit updates once per Δt.</li>
 *   <li>The no-load reference is a running minimum reset on a fixed probe
 *       interval, rather than Netflix's pair of long and short window
 *       estimators.</li>
 *   <li>IASR's guardrails still apply on top, as they do for any controller
 *       under this engine.</li>
 * </ul>
 * These make it a fair comparison under identical conditions rather than a
 * benchmark of two different measurement pipelines.
 */
@Slf4j
public class GradientController implements Controller {

    /** Ticks between resets of the no-load reference. */
    private static final int PROBE_INTERVAL_TICKS = 30;

    /** Fraction of the limit that must be in use before it may move. */
    private static final double USAGE_THRESHOLD = 0.5;

    private final double minGradient;
    private final double smoothing;
    private final int minLimit;
    private final int maxLimit;

    private double rttNoLoadMs = Double.NaN;
    private double limit = Double.NaN;
    private int ticksUntilProbe = PROBE_INTERVAL_TICKS;

    public GradientController(int minLimit, int maxLimit, double minGradient, double smoothing) {
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.minGradient = minGradient;
        this.smoothing = smoothing;
    }

    /** Netflix's defaults: halve at most per update, 20% smoothing. */
    public GradientController(int minLimit, int maxLimit) {
        this(minLimit, maxLimit, 0.5, 0.2);
    }

    @Override
    public ControlAction decide(MetricsSnapshot snapshot, List<Actuator> actuators) {
        Actuator limiter = find(actuators, ConcurrencyLimiter.ACTUATOR_NAME);
        if (limiter == null) {
            return ControlAction.NONE;
        }

        int current = limiter.currentValue();
        if (Double.isNaN(limit)) {
            limit = current;
        }

        double latency = snapshot.get(MetricNames.LATENCY_P95_MS, 0.0);
        double inflight = snapshot.get(MetricNames.INFLIGHT, 0.0);
        double queue = snapshot.get(MetricNames.QUEUE_LENGTH, 0.0);

        if (latency <= 0) {
            return ControlAction.builder().hold(limiter.name(), "gradient_no_samples").build();
        }

        // Periodically forget the minimum, so the reference can rise when the
        // system itself got slower for reasons the limit cannot fix.  Without
        // this the gradient stays permanently below one and the limit decays.
        if (--ticksUntilProbe <= 0 || Double.isNaN(rttNoLoadMs)) {
            rttNoLoadMs = latency;
            ticksUntilProbe = PROBE_INTERVAL_TICKS;
        } else {
            rttNoLoadMs = Math.min(rttNoLoadMs, latency);
        }

        // Do not move a limit nothing is pressing against: the measurement
        // would reflect an idle system rather than its capacity.
        if (inflight < USAGE_THRESHOLD * limit && queue <= 0) {
            return ControlAction.builder().hold(limiter.name(), "gradient_underutilised").build();
        }

        double gradient = Math.max(minGradient, Math.min(1.0, rttNoLoadMs / latency));
        double allowance = Math.sqrt(limit);
        double target = limit * gradient + allowance;
        limit = limit * (1 - smoothing) + target * smoothing;
        limit = Math.max(minLimit, Math.min(limit, maxLimit));

        int newLimit = (int) Math.round(limit);
        if (newLimit == current) {
            return ControlAction.builder().hold(limiter.name(), "gradient_no_change").build();
        }

        // Label by the direction actually taken, not by the gradient: the
        // allowance term can push the limit up even when the gradient is below
        // one, and a record saying "shrink" next to a raised limit is worse
        // than no record at all.
        String reason = newLimit < current ? "gradient_shrink" : "gradient_grow";
        log.debug("Gradient: {} → {} (rttNoLoad={} rtt={} g={})",
                current, newLimit, rttNoLoadMs, latency, gradient);
        return ControlAction.builder().set(limiter.name(), newLimit, reason).build();
    }

    private static Actuator find(List<Actuator> actuators, String name) {
        for (Actuator a : actuators) {
            if (name.equals(a.name())) return a;
        }
        return null;
    }
}
