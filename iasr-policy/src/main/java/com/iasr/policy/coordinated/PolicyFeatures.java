package com.iasr.policy.coordinated;

import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.resource.SoftResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature contract version 3 of the coordinated policy; the twin of
 * {@code iasr_learning/features.py}. Every expression is evaluated in the same
 * order and precision on both sides, so a model trained offline sees in the
 * JVM exactly the numbers it was fitted on.
 * <p>
 * The instance carries the exponential averages of consecutive active windows
 * and is therefore stateful: one per control loop.
 */
public final class PolicyFeatures {
    public static final int VERSION = 3;
    static final int GLOBAL = 11, PER_RESOURCE = 8, PER_ACTION = 4;
    private static final double MIN_SERVED_SHARE = 0.05;

    private final List<SoftResource> resources;
    private final double sloMs, alpha, ratioCap;
    private final Map<String, Double> average = new HashMap<>();
    private final double[] demand, load;
    private int count;

    public PolicyFeatures(List<SoftResource> resources, double sloMs, double alpha, double ratioCap) {
        this.resources = List.copyOf(resources);
        this.sloMs = sloMs;
        this.alpha = alpha;
        this.ratioCap = ratioCap;
        this.demand = new double[resources.size()];
        this.load = new double[resources.size()];
    }

    public static int stateSize(int resources) { return GLOBAL + PER_RESOURCE * resources + Math.max(0, resources - 1); }
    public static int actionSize(int resources) { return PER_ACTION * resources + Math.max(0, resources - 1); }

    public void reset() { count = 0; average.clear(); }
    public int count() { return count; }

    static double number(MetricsSnapshot state, String key, double fallback) {
        double value = state.get(key, Double.NaN);
        return Double.isFinite(value) ? value : fallback;
    }

    /** A window carries information when it served or rejected something. */
    public static boolean isActive(MetricsSnapshot state) {
        return number(state, MetricNames.GOODPUT_RPS, 0) >= 1.0 || number(state, MetricNames.TIMEOUT_RATE, 0) > 0.0;
    }

    /** Share of the offered requests answered successfully within the objective. */
    public static double servedWithinSlo(MetricsSnapshot state) {
        double share = 1.0 - number(state, MetricNames.TIMEOUT_RATE, 0) - number(state, MetricNames.ERROR_RATE, 0);
        if (share < 0.0) share = 0.0;
        return share * number(state, MetricNames.SLO_GOOD_FRACTION, 1.0);
    }

    private double clip(double value) { return value < 0.0 ? 0.0 : Math.min(value, ratioCap); }

    private double update(String key, double value) {
        Double previous = average.get(key);
        double next = previous == null ? value : previous + alpha * (value - previous);
        average.put(key, next);
        return next;
    }

    /** Fold one active window in and return its state features. */
    public double[] observe(MetricsSnapshot state, int[] capacities) {
        double[] f = new double[stateSize(resources.size())];
        double p95 = number(state, MetricNames.LATENCY_P95_MS, 0);
        double rejected = number(state, MetricNames.TIMEOUT_RATE, 0);
        double errors = number(state, MetricNames.ERROR_RATE, 0);
        double within = number(state, MetricNames.SLO_GOOD_FRACTION, 1.0);
        double served = servedWithinSlo(state);
        double share = 1.0 - rejected - errors;
        if (share < MIN_SERVED_SHARE) share = MIN_SERVED_SHARE;
        double offered = update("offered", number(state, MetricNames.GOODPUT_RPS, 0) / share);
        int i = 0;
        f[i++] = clip(p95 / sloMs);
        f[i++] = clip(update("p95", p95) / sloMs);
        f[i++] = clip(update("mean", number(state, MetricNames.LATENCY_MEAN_MS, 0)) / sloMs);
        f[i++] = rejected;
        f[i++] = update("rejected", rejected);
        f[i++] = update("errors", errors);
        f[i++] = number(state, MetricNames.CPU_PROCESS, 0);
        f[i++] = within;
        f[i++] = update("within", within);
        f[i++] = served;
        f[i++] = update("served", served);
        for (int r = 0; r < resources.size(); r++) {
            SoftResource resource = resources.get(r);
            String name = resource.actuatorName();
            double capacity = capacities[r] > 1 ? capacities[r] : 1;
            double usage = number(state, resource.usageMetric(), 0);
            double hold = number(state, resource.holdMetric(), 0);
            double usageAvg = update(name + ".usage", usage);
            double backlogAvg = update(name + ".backlog", number(state, resource.backlogMetric(), 0));
            double waitAvg = update(name + ".wait", number(state, resource.waitMetric(), 0));
            double holdAvg = update(name + ".hold", hold);
            demand[r] = offered * holdAvg / 1000.0;
            load[r] = usageAvg + backlogAvg;
            f[i++] = capacity;
            f[i++] = clip(usageAvg / capacity);
            f[i++] = clip(backlogAvg / capacity);
            f[i++] = clip(waitAvg / sloMs);
            f[i++] = clip(holdAvg / sloMs);
            f[i++] = clip(hold / sloMs);
            f[i++] = clip(demand[r] / capacity);
            f[i++] = clip(usage / capacity);
        }
        for (int r = 0; r + 1 < resources.size(); r++) {
            f[i++] = clip((double) capacities[r + 1] / (double) (capacities[r] > 1 ? capacities[r] : 1));
        }
        count++;
        return f;
    }

    /** Describe a joint move by the configuration it leaves behind; valid after {@link #observe}. */
    public double[] action(int[] capacities, int[] targets) {
        double[] f = new double[actionSize(resources.size())];
        int i = 0;
        for (int r = 0; r < resources.size(); r++) {
            double capacity = capacities[r] > 1 ? capacities[r] : 1;
            double after = targets[r] > 1 ? targets[r] : 1;
            f[i++] = ((double) targets[r] - capacity) / capacity;
            f[i++] = targets[r];
            f[i++] = clip(demand[r] / after);
            f[i++] = clip(load[r] / after);
        }
        for (int r = 0; r + 1 < resources.size(); r++) {
            f[i++] = clip((double) targets[r + 1] / (double) (targets[r] > 1 ? targets[r] : 1));
        }
        return f;
    }

    /** Coarse kind of state under which support for an action is counted. */
    public String bin(MetricsSnapshot state) {
        double p95 = number(state, MetricNames.LATENCY_P95_MS, 0);
        StringBuilder bin = new StringBuilder(p95 < 0.7 * sloMs ? "comfortable" : p95 <= sloMs ? "within_slo" : "over_slo");
        for (SoftResource resource : resources) {
            bin.append('/').append(number(state, resource.backlogMetric(), 0) > 0 ? "backlog" : "free");
        }
        return bin.toString();
    }

    /** Signs of a joint move: the key of the support table. */
    public static String direction(int[] capacities, int[] targets) {
        StringBuilder key = new StringBuilder();
        for (int r = 0; r < capacities.length; r++) {
            if (r > 0) key.append(',');
            key.append(Integer.compare(targets[r], capacities[r]));
        }
        return key.toString();
    }
}
