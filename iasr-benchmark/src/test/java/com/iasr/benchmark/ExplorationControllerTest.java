package com.iasr.benchmark;

import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExplorationControllerTest {
    private static final List<Actuator> ACTUATORS = List.of(new FixedActuator("concurrency_limit", 10, 1, 20),
            new FixedActuator("hikari_max_pool", 5, 1, 10));
    private record FixedActuator(String name, int currentValue, int minValue, int maxValue) implements Actuator {
        public void apply(int value) { throw new AssertionError("Controller must not apply actions"); }
    }

    private ExplorationController controller(double epsilon, long seed) {
        var space = new JointActionSpace(List.of(new ActuatorGuardrail("concurrency_limit", 1, 20, 5, 0),
                new ActuatorGuardrail("hikari_max_pool", 1, 10, 2, 0, false)), 150, 1, 5);
        return new ExplorationController(space,
                (s, a) -> ControlAction.builder().set("concurrency_limit", 9, "baseline").build(), epsilon, seed);
    }

    private MetricsSnapshot state(int tick, double goodput) {
        return MetricsSnapshot.builder().put(MetricNames.CONTROL_TICK, tick).put(MetricNames.GOODPUT_RPS, goodput)
                .put(MetricNames.LATENCY_P95_MS, 50).put(MetricNames.cooldownRemaining("concurrency_limit"), 0)
                .put(MetricNames.cooldownRemaining("hikari_max_pool"), 0).build();
    }

    @Test
    void seedReproducesDecisionsAndReachesJointActions() {
        var first = controller(1, 123);
        var second = controller(1, 123);
        var distinct = new java.util.HashSet<java.util.Map<String, Integer>>();
        for (int i = 0; i < 300; i++) {
            var a = first.decide(state(i, 120), ACTUATORS);
            assertThat(a.desiredValues()).isEqualTo(second.decide(state(i, 120), ACTUATORS).desiredValues());
            if (i > 3 && i % 5 == 0) distinct.add(a.desiredValues());
        }
        assertThat(distinct).hasSize(9);
    }

    @Test
    void baselineHandlesWarmupAndIdleWhileNonExploringPolicyHolds() {
        var controller = controller(0, 123);
        for (int i = 0; i < 3; i++) assertThat(controller.decide(state(i, 120), ACTUATORS)
                .reason("concurrency_limit")).isEqualTo("baseline");
        assertThat(controller.decide(state(3, 120), ACTUATORS).reason("concurrency_limit")).isEqualTo("explore_hold");
        assertThat(controller.decide(state(4, 0), ACTUATORS).reason("concurrency_limit")).isEqualTo("baseline");
    }
    private JointActionSpace balancedSpace() {
        return new JointActionSpace(List.of(new ActuatorGuardrail("concurrency_limit", 1, 20, 5, 0),
                new ActuatorGuardrail("hikari_max_pool", 1, 10, 2, 0, false)), 150, 1, 5);
    }

    @Test
    void balancedSamplingCoversAllLegalJointActionsEvenlyAndReproducibly() {
        var first = new ExplorationController(balancedSpace(), (s, a) -> com.iasr.core.controller.ControlAction.NONE,
                1, 42, ExplorationController.Sampling.BALANCED, 150);
        var second = new ExplorationController(balancedSpace(), (s, a) -> com.iasr.core.controller.ControlAction.NONE,
                1, 42, ExplorationController.Sampling.BALANCED, 150);
        var counts = new java.util.HashMap<java.util.Map<String, Integer>, Integer>();
        for (int i = 0; i < 500; i++) {
            var action = first.decide(state(i, 120), ACTUATORS);
            assertThat(action.desiredValues()).isEqualTo(second.decide(state(i, 120), ACTUATORS).desiredValues());
            if (i > 3 && i % 5 == 0) counts.merge(action.desiredValues(), 1, Integer::sum);
        }
        assertThat(counts).hasSize(9);
        assertThat(java.util.Collections.max(counts.values()) - java.util.Collections.min(counts.values())).isLessThanOrEqualTo(1);
        assertThat(first.parameters()).containsEntry("sampling", "balanced");
    }

    @Test
    void balancedSamplingRechecksSloAndCooldownMaskOnEveryTick() {
        var space = balancedSpace();
        var controller = new ExplorationController(space, (s, a) -> com.iasr.core.controller.ControlAction.NONE,
                1, 7, ExplorationController.Sampling.BALANCED, 150);
        for (int i = 0; i < 3; i++) controller.decide(state(i, 120), ACTUATORS);
        for (int i = 3; i < 100; i++) {
            var snapshot = MetricsSnapshot.builder().putAll(state(i, 120).asMap())
                    .put(MetricNames.LATENCY_P95_MS, i % 2 == 0 ? 200 : 50)
                    .put(MetricNames.cooldownRemaining("concurrency_limit"), i % 3 == 0 ? 1 : 0).build();
            var action = controller.decide(snapshot, ACTUATORS);
            var delta = new JointActionSpace.Delta(action.getOrDefault("concurrency_limit", 10) - 10,
                    action.getOrDefault("hikari_max_pool", 5) - 5);
            assertThat(space.candidates(snapshot, ACTUATORS)).contains(delta);
        }
    }

    @Test
    void jointSamplingHoldsBetweenPoolTicksAndBalancesLegalPairs() {
        var space = balancedSpace();
        var controller = new ExplorationController(space, (s, a) -> ControlAction.NONE,
                1, 42, ExplorationController.Sampling.JOINT, 150);
        for (int i = 0; i < 3; i++) controller.decide(state(i, 120), ACTUATORS);
        var pairs = new java.util.HashSet<java.util.Map<String, Integer>>();
        for (int i = 3; i <= 20; i++) {
            var action = controller.decide(state(i, 120), ACTUATORS);
            if (i % 5 == 0) {
                assertThat(action.reason("concurrency_limit")).isEqualTo("explore_joint");
                assertThat(action.desiredValues().get("concurrency_limit")).isNotEqualTo(10);
                assertThat(action.desiredValues().get("hikari_max_pool")).isNotEqualTo(5);
                pairs.add(action.desiredValues());
            } else {
                assertThat(action.desiredValues()).isEqualTo(java.util.Map.of("concurrency_limit", 10, "hikari_max_pool", 5));
            }
        }
        assertThat(pairs).hasSize(4);
        var cooldown = MetricsSnapshot.builder().putAll(state(25, 120).asMap())
                .put(MetricNames.cooldownRemaining("concurrency_limit"), 1).build();
        var action = controller.decide(cooldown, ACTUATORS);
        var delta = new JointActionSpace.Delta(action.desiredValues().get("concurrency_limit") - 10,
                action.desiredValues().get("hikari_max_pool") - 5);
        assertThat(delta.concurrency()).isZero();
        assertThat(space.candidates(cooldown, ACTUATORS)).contains(delta);
    }

}
