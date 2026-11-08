package com.iasr.core.controller;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.guardrail.Guardrails;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.support.TestActuator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JointActionSpaceTest {
    private final List<ActuatorGuardrail> rules = List.of(
            new ActuatorGuardrail(JointActionSpace.LIMIT, 1, 20, 5, 1, true, 10),
            new ActuatorGuardrail(JointActionSpace.POOL, 1, 10, 2, 2, false));
    private final List<Actuator> actuators = List.of(
            new TestActuator(JointActionSpace.LIMIT, 10, 1, 20),
            new TestActuator(JointActionSpace.POOL, 5, 1, 10));
    private final JointActionSpace space = new JointActionSpace(rules, 150, 1, 5);

    private MetricsSnapshot state(int tick, double latency, int limitCooldown, int poolCooldown) {
        return MetricsSnapshot.builder().put(MetricNames.CONTROL_TICK, tick)
                .put(MetricNames.LATENCY_P95_MS, latency)
                .put(MetricNames.cooldownRemaining(JointActionSpace.LIMIT), limitCooldown)
                .put(MetricNames.cooldownRemaining(JointActionSpace.POOL), poolCooldown).build();
    }

    @Test
    void permitsNineJointActionsOnlyOnPoolCadence() {
        assertThat(space.candidates(state(5, 100, 0, 0), actuators)).hasSize(9);
        assertThat(space.candidates(state(6, 100, 0, 0), actuators)).hasSize(3)
                .allSatisfy(a -> assertThat(a.pool()).isZero());
    }

    @Test
    void cooldownAndSloBlockRemoveActionsBeforeSampling() {
        assertThat(space.candidates(state(5, 200, 1, 0), actuators)).hasSize(3)
                .allSatisfy(a -> assertThat(a.concurrency()).isZero());
        assertThat(space.candidates(state(5, 200, 0, 0), actuators)).hasSize(6)
                .allSatisfy(a -> assertThat(a.concurrency()).isLessThanOrEqualTo(0));
        assertThat(space.candidates(state(5, 100, 1, 2), actuators)).containsExactly(JointActionSpace.HOLD);
    }

    @Test
    void allCandidatesPassTheActualGuardrailsUnchanged() {
        var state = state(5, 100, 0, 0);
        for (var delta : space.candidates(state, actuators)) {
            var desired = space.action(delta, actuators, "test");
            var applied = new Guardrails(rules, 150).enforce(desired, actuators, state);
            for (Actuator actuator : actuators) {
                assertThat(applied.getOrDefault(actuator.name(), actuator.currentValue()))
                        .isEqualTo(desired.getOrDefault(actuator.name(), actuator.currentValue()));
            }
        }
    }

    @Test
    void boundsPinnedResourcesAndAbsentContextAreRespected() {
        ((TestActuator) actuators.get(0)).setSilently(1);
        assertThat(space.candidates(state(5, 100, 0, 0), actuators))
                .allSatisfy(a -> assertThat(a.concurrency()).isGreaterThanOrEqualTo(0));
        var pinned = new JointActionSpace(List.of(rules.get(0),
                new ActuatorGuardrail(JointActionSpace.POOL, 5, 5, 2, 2, false)), 150, 1, 5);
        assertThat(pinned.candidates(state(5, 100, 0, 0), actuators))
                .allSatisfy(a -> assertThat(a.pool()).isZero());
        assertThat(space.candidates(MetricsSnapshot.builder().build(), actuators)).isEmpty();
    }
}
