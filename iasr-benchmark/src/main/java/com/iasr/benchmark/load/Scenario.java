package com.iasr.benchmark.load;

import com.iasr.benchmark.app.BackendModel;

import java.util.List;

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
        DependencySchedule dependency
) {

    /** How the downstream dependency behaves over time. */
    @FunctionalInterface
    public interface DependencySchedule {
        void applyAt(double elapsedSeconds, BackendModel backend);
    }

    private static final long BASE_SERVICE_MS = 50;

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
        return new Scenario(
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
        return new Scenario(
                "spike",
                "Under a sudden 3x burst, does the loop protect latency, and does it recover after?",
                45_000,
                t -> (t >= 12 && t < 32) ? 400 : 120,
                steadyDependency());
    }

    /** A slow climb from far below to far above capacity. */
    public static Scenario ramp() {
        return new Scenario(
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
        return new Scenario(
                "slow-dependency",
                "When the dependency degrades under steady load, does the loop adapt to the new capacity?",
                45_000,
                t -> 150,
                (t, backend) -> backend.setServiceTimeMs(
                        (t >= 15 && t < 35) ? 120 : BASE_SERVICE_MS));
    }
}
