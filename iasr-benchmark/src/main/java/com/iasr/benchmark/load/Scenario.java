package com.iasr.benchmark.load;

import com.iasr.benchmark.app.BackendModel;

import java.util.List;
import java.util.Map;

/**
 * A load profile plus the dependency behaviour that goes with it.
 * <p>
 * Each scenario is built around a specific way a static configuration can be
 * wrong, because a control loop can only earn its keep where a fixed setting
 * cannot be right for the whole run.
 */
public record Scenario(
        String name,
        String question,
        long durationMs,
        LoadGenerator.RateSchedule rate,
    DependencySchedule dependency,
    Map<String, Object> definition
) {
    public Scenario {
        definition = Map.copyOf(definition);
    }

    public Scenario(String name, String question, long durationMs,
                    LoadGenerator.RateSchedule rate, DependencySchedule dependency) {
        this(name, question, durationMs, rate, dependency, Map.of());
    }

    /** How the downstream dependency behaves over time. */
    @FunctionalInterface
    public interface DependencySchedule {
        void applyAt(double elapsedSeconds, BackendModel backend);
    }

    /** Dependency service time; see BenchmarkRunner#SLO_MS for why it varies. */
    private static final long BASE_SERVICE_MS = Long.getLong("bench.db.serviceMs", 50L);

    /** Factor by which the dependency slows down in the degradation scenario. */
    private static final double DEGRADED_FACTOR = positiveProperty("bench.degradedFactor", 2.4);
    private static final double LOAD_SCALE = positiveProperty("bench.loadScale", 1.0);
    private static final double DURATION_SCALE = positiveProperty("bench.durationScale", 1.0);

    private static double positiveProperty(String name, double fallback) {
        double value = Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be finite and positive");
        return value;
    }

    private static Scenario scaled(String name, String question, long durationMs,
                                   LoadGenerator.RateSchedule rate, DependencySchedule dependency) {
        long duration = Math.round(durationMs * DURATION_SCALE);
        if (duration < 1000 || durationMs * DURATION_SCALE > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Scenario duration must be at least 1 second and fit a long");
        }
        return new Scenario(name, question, duration,
                t -> LOAD_SCALE * rate.ratePerSecondAt(t / DURATION_SCALE),
                (t, backend) -> dependency.applyAt(t / DURATION_SCALE, backend),
                Map.of("profileVersion", 1, "name", name, "loadScale", LOAD_SCALE,
                        "durationScale", DURATION_SCALE, "degradedFactor", DEGRADED_FACTOR,
                        "baseServiceMs", BASE_SERVICE_MS));
    }

    /** Dependency keeps its baseline latency for the whole run. */
    private static DependencySchedule steadyDependency() {
        return (t, backend) -> backend.setServiceTimeMs(BASE_SERVICE_MS);
    }

    public static List<Scenario> all() {
        return List.of(steady(), spike(), ramp(), slowDependency());
    }

    /**
     * Baseline sanity check: comfortably below capacity throughout.
     * A control loop that helps under stress but hurts at normal load is not
     * worth running, so this scenario exists to catch that.
     */
    public static Scenario steady() {
        return scaled(
                "steady",
                "Does the loop do any harm when load is comfortably within capacity?",
                30_000,
                t -> 120,
                steadyDependency());
    }

    /**
     * A sudden burst well past capacity, then recovery.
     * Tests both shedding under overload and giving capacity back afterwards.
     */
    public static Scenario spike() {
        return scaled(
                "spike",
                "Under a sudden 3x burst, does the loop protect latency, and does it recover after?",
                45_000,
                t -> (t >= 12 && t < 32) ? 400 : 120,
                steadyDependency());
    }

    /** A slow climb from far below to far above capacity. */
    public static Scenario ramp() {
        return scaled(
                "ramp",
                "As load crosses capacity, where does each configuration start to break?",
                45_000,
                t -> 60 + (t / 45.0) * 440,
                steadyDependency());
    }

    /**
     * Constant load, but the dependency slows down by 2.4x mid-run — capacity
     * collapses from 240 rps to 100 rps while offered load stays at 150 rps.
     * This is the case a static limit cannot handle by construction: the correct
     * limit before and after the degradation differ by more than a factor of two.
     */
    public static Scenario slowDependency() {
        return scaled(
                "slow-dependency",
                "When the dependency degrades under steady load, does the loop adapt to the new capacity?",
                45_000,
                t -> 150,
                (t, backend) -> backend.setServiceTimeMs(
                        (t >= 15 && t < 35)
                                ? Math.round(BASE_SERVICE_MS * DEGRADED_FACTOR)
                                : BASE_SERVICE_MS));
    }
}
