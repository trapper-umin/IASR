package com.iasr.core.resource;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinatedActionSpaceTest {
    static final List<ActionLevel> LIMIT_LEVELS = List.of(new ActionLevel(-0.25, 2), new ActionLevel(-0.1, 1),
            ActionLevel.HOLD, new ActionLevel(0.1, 1), new ActionLevel(0.25, 2));
    static final List<ActionLevel> POOL_LEVELS = List.of(new ActionLevel(-0.15, 1), ActionLevel.HOLD, new ActionLevel(0.15, 1));

    static Actuator actuator(String name, int value, int min, int max) {
        return new Actuator() {
            public String name() { return name; }
            public int currentValue() { return value; }
            public int minValue() { return min; }
            public int maxValue() { return max; }
            public void apply(int v) { throw new AssertionError(); }
        };
    }

    static CoordinatedActionSpace space(boolean blockPoolGrowth) {
        return new CoordinatedActionSpace(List.of(
                new CoordinatedActionSpace.Managed(SoftResource.CONCURRENCY,
                        new ActuatorGuardrail("concurrency_limit", 2, 400, 20, 1, true, 50), LIMIT_LEVELS),
                new CoordinatedActionSpace.Managed(SoftResource.CONNECTION_POOL,
                        new ActuatorGuardrail("hikari_max_pool", 2, 16, 2, 2, blockPoolGrowth), POOL_LEVELS)), 150);
    }

    static MetricsSnapshot state(double p95, int limitCooldown, int poolCooldown) {
        return MetricsSnapshot.builder().put(MetricNames.LATENCY_P95_MS, p95)
                .put(MetricNames.cooldownRemaining("concurrency_limit"), limitCooldown)
                .put(MetricNames.cooldownRemaining("hikari_max_pool"), poolCooldown).build();
    }

    @Test void relativeLevelsScaleWithTheCapacityAndNeverGoBelowTheMinimumStep() {
        assertThat(new ActionLevel(-0.25, 2).delta(40)).isEqualTo(-10);
        assertThat(new ActionLevel(-0.25, 2).delta(5)).isEqualTo(-2);
        assertThat(new ActionLevel(0.1, 1).delta(4)).isEqualTo(1);
        assertThat(new ActionLevel(0.1, 1).delta(25)).isEqualTo(3);   // 2.5 rounds half up
        assertThat(ActionLevel.HOLD.delta(100)).isZero();
        assertThatThrownBy(() -> new ActionLevel(0.1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void jointCandidatesAreTheProductOfEveryResourceAndStartWithHold() {
        var actuators = List.of(actuator("concurrency_limit", 40, 1, 1000), actuator("hikari_max_pool", 8, 1, 100));
        List<int[]> candidates = space(false).candidates(state(100, 0, 0), actuators);
        assertThat(candidates).hasSize(15);
        assertThat(candidates.get(0)).containsExactly(40, 8);
        assertThat(candidates).anySatisfy(c -> assertThat(c).containsExactly(30, 9));
        assertThat(candidates).anySatisfy(c -> assertThat(c).containsExactly(50, 7));
    }

    @Test void growthIsNotOfferedWhileTheObjectiveIsViolatedUnlessTheResourceIsExempt() {
        var actuators = List.of(actuator("concurrency_limit", 40, 1, 1000), actuator("hikari_max_pool", 8, 1, 100));
        List<int[]> candidates = space(false).candidates(state(400, 0, 0), actuators);
        assertThat(candidates).allSatisfy(c -> assertThat(c[0]).isLessThanOrEqualTo(40));
        assertThat(candidates).anySatisfy(c -> assertThat(c[1]).isEqualTo(9));
        assertThat(space(true).candidates(state(400, 0, 0), actuators)).allSatisfy(c -> assertThat(c[1]).isLessThanOrEqualTo(8));
    }

    @Test void aResourceInCooldownCanOnlyHold() {
        var actuators = List.of(actuator("concurrency_limit", 40, 1, 1000), actuator("hikari_max_pool", 8, 1, 100));
        List<int[]> candidates = space(false).candidates(state(100, 0, 1), actuators);
        assertThat(candidates).hasSize(5).allSatisfy(c -> assertThat(c[1]).isEqualTo(8));
    }

    @Test void targetsAreClampedToTheBoundsAndDuplicatesCollapse() {
        var actuators = List.of(actuator("concurrency_limit", 3, 1, 1000), actuator("hikari_max_pool", 16, 1, 100));
        CoordinatedActionSpace space = space(false);
        assertThat(space.targets(0, state(100, 0, 0), actuators)).containsExactly(3, 2, 4, 5);
        assertThat(space.targets(1, state(100, 0, 0), actuators)).containsExactly(16, 14);
    }

    @Test void stepLimitsOfTheGuardrailCapEveryLevel() {
        var managed = new CoordinatedActionSpace.Managed(SoftResource.CONCURRENCY,
                new ActuatorGuardrail("concurrency_limit", 2, 400, 5, 0, false, 8), LIMIT_LEVELS);
        assertThat(CoordinatedActionSpace.targets(managed, 100, 2, 400, 0, 100, 150)).containsExactly(100, 92, 105);
    }

    @Test void aMissingActuatorYieldsNoCandidates() {
        assertThat(space(false).candidates(state(100, 0, 0), List.of(actuator("concurrency_limit", 40, 1, 1000)))).isEmpty();
    }
}
