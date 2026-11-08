package com.iasr.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.guardrail.Guardrails;
import com.iasr.core.metrics.MetricNames;
import com.iasr.core.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class LearnedControllerTest {
    static List<Actuator> actuators(FqiFeatures.Observation row, PolicyContext context) {
        return List.of(resource(JointActionSpace.LIMIT, row, context), resource(JointActionSpace.POOL, row, context));
    }
    static Actuator resource(String name, FqiFeatures.Observation row, PolicyContext context) {
        var rule = context.rules().get(name);
        return new Actuator() {
            public String name() { return name; }
            public int currentValue() { return row.resourcesBefore().get(name); }
            public int minValue() { return rule.minValue(); }
            public int maxValue() { return rule.maxValue(); }
            public void apply(int value) { throw new AssertionError("Controller must never apply actions directly"); }
        };
    }
    static MetricsSnapshot snapshot(FqiFeatures.Observation row) {
        var result = MetricsSnapshot.builder();
        row.state().forEach((key, value) -> result.put(key, value == null ? Double.NaN : value));
        row.cooldownRemaining().forEach((key, value) -> result.put(MetricNames.cooldownRemaining(key), value));
        return result.put(MetricNames.OBSERVATION_ELAPSED_SEC, row.elapsedSeconds()).build();
    }
    static JsonNode supported() throws Exception {
        for (JsonNode node : FqiModelTest.fixtures()) if (!node.path("decision").path("fallback").asBoolean()) return node;
        throw new AssertionError("No supported fixture");
    }
    static ControlAction feed(LearnedController controller, JsonNode fixture) throws Exception {
        ControlAction last = null;
        var context = FqiModelTest.context(fixture.get("context"));
        for (var row : FqiModelTest.history(fixture.get("history"))) last = controller.decide(snapshot(row), actuators(row, context));
        return last;
    }
    static Controller baseline(AtomicInteger calls) {
        return (state, resources) -> {
            calls.incrementAndGet();
            return ControlAction.builder().set(JointActionSpace.LIMIT, 12, "baseline_test").build();
        };
    }

    @Test void warmsBaselineEveryTickAndUsesFourCompleteWindows() throws Exception {
        var fixture = supported(); var context = FqiModelTest.context(fixture.get("context"));
        AtomicInteger calls = new AtomicInteger();
        var controller = new LearnedController(FqiModel.loadPrototype(FqiModelTest.MODEL), context, baseline(calls), Long.MAX_VALUE);
        assertThat(feed(controller, fixture).reason(JointActionSpace.LIMIT)).isEqualTo("learned_policy");
        assertThat(calls.get()).isEqualTo(4);
        assertThat(controller.statistics()).containsEntry("modelDecisions", 1L).containsEntry("decisions", 4L);
        assertThat(controller.statistics().get("fallbacks")).isEqualTo(Map.of("short_history", 3L));
    }

    @Test void fallsBackOnUnsupportedHistoryAndRetainsBaselineProposal() throws Exception {
        var fixture = FqiModelTest.fixtures().get(FqiModelTest.fixtures().size() - 1);
        var controller = new LearnedController(FqiModel.loadPrototype(FqiModelTest.MODEL), FqiModelTest.context(fixture.get("context")), baseline(new AtomicInteger()), Long.MAX_VALUE);
        var action = feed(controller, fixture);
        assertThat(action.desiredValues()).containsEntry(JointActionSpace.LIMIT, 12);
        assertThat(action.reason(JointActionSpace.LIMIT)).isEqualTo("learned_fallback_no_empirical_support:baseline_test");
    }

    @Test void resetsHistoryOnGapOrIrregularInterval() throws Exception {
        var fixture = supported(); var context = FqiModelTest.context(fixture.get("context"));
        var controller = new LearnedController(FqiModel.loadPrototype(FqiModelTest.MODEL), context, baseline(new AtomicInteger()), Long.MAX_VALUE);
        feed(controller, fixture);
        var row = FqiModelTest.history(fixture.get("history")).get(3); var resources = actuators(row, context);
        var gap = MetricsSnapshot.builder().putAll(snapshot(row).asMap()).put(MetricNames.CONTROL_TICK, row.state().get(MetricNames.CONTROL_TICK) + 2).build();
        assertThat(controller.decide(gap, resources).reason(JointActionSpace.LIMIT)).startsWith("learned_fallback_short_history");
        var irregular = MetricsSnapshot.builder().putAll(gap.asMap()).put(MetricNames.OBSERVATION_ELAPSED_SEC, 5).build();
        assertThat(controller.decide(irregular, resources).reason(JointActionSpace.LIMIT)).startsWith("learned_fallback_invalid_observation");
        assertThat(controller.decide(snapshot(row), resources).reason(JointActionSpace.LIMIT)).startsWith("learned_fallback_short_history");
    }

    @Test void deadlineReturnsBaselineWithoutBackgroundWork() throws Exception {
        var fixture = supported(); AtomicLong time = new AtomicLong();
        var controller = new LearnedController(FqiModel.loadPrototype(FqiModelTest.MODEL), FqiModelTest.context(fixture.get("context")), baseline(new AtomicInteger()), 2, time::getAndIncrement);
        assertThat(feed(controller, fixture).reason(JointActionSpace.LIMIT)).startsWith("learned_fallback_budget");
        assertThat(controller.statistics()).containsEntry("modelDecisions", 0L);
    }

    @Test void fallbackStillPassesThroughEngineGuardrails() throws Exception {
        var fixture = supported(); var context = FqiModelTest.context(fixture.get("context"));
        var row = FqiModelTest.history(fixture.get("history")).get(0); var resources = actuators(row, context);
        var controller = new LearnedController(FqiModel.loadPrototype(FqiModelTest.MODEL), context,
                (s, a) -> ControlAction.builder().set(JointActionSpace.LIMIT, Integer.MAX_VALUE).build(), Long.MAX_VALUE);
        var proposal = controller.decide(snapshot(row), resources);
        var applied = new Guardrails(List.copyOf(context.rules().values()), context.sloMs()).enforce(proposal, resources, snapshot(row));
        assertThat(applied.getOrDefault(JointActionSpace.LIMIT, row.resourcesBefore().get(JointActionSpace.LIMIT)))
                .isLessThanOrEqualTo(row.resourcesBefore().get(JointActionSpace.LIMIT) + context.rules().get(JointActionSpace.LIMIT).maxStep());
    }
}
