package com.iasr.policy.coordinated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iasr.core.actuator.Actuator;
import com.iasr.core.controller.ControlAction;
import com.iasr.core.controller.Controller;
import com.iasr.core.guardrail.ActuatorGuardrail;
import com.iasr.core.metrics.MetricsSnapshot;
import com.iasr.core.resource.SoftResource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Replays windows recorded on the stand through the JVM controller and checks
 * every decision against the Python reference that produced the fixture.
 */
class CoordinatedControllerTest {
    static final Path POLICY = Path.of("src/test/resources/coordinated/policy.json");
    static final Path FIXTURE = Path.of("src/test/resources/coordinated/fixture.json");
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode fixture() throws Exception { return JSON.readTree(FIXTURE.toFile()); }

    static List<ActuatorGuardrail> rules(JsonNode fixture) {
        List<ActuatorGuardrail> rules = new ArrayList<>();
        for (JsonNode rule : fixture.get("guardrails")) {
            rules.add(new ActuatorGuardrail(rule.get("actuatorName").asText(), rule.get("minValue").asInt(),
                    rule.get("maxValue").asInt(), rule.get("maxStep").asInt(), rule.get("cooldownTicks").asInt(),
                    rule.get("blockIncreaseOnSloViolation").asBoolean(), rule.get("maxStepDown").asInt()));
        }
        return rules;
    }

    static MetricsSnapshot snapshot(JsonNode window) {
        MetricsSnapshot.Builder builder = MetricsSnapshot.builder();
        window.get("state").fields().forEachRemaining(e -> { if (e.getValue().isNumber()) builder.put(e.getKey(), e.getValue().asDouble()); });
        return builder.build();
    }

    static List<Actuator> actuators(PolicyModel model, List<ActuatorGuardrail> rules, JsonNode window) {
        List<Actuator> result = new ArrayList<>();
        for (int i = 0; i < model.resources().size(); i++) {
            String name = model.resources().get(i).signals().actuatorName();
            ActuatorGuardrail rule = rules.stream().filter(r -> r.actuatorName().equals(name)).findFirst().orElseThrow();
            int value = window.get("capacities").get(i).asInt();
            result.add(new Actuator() {
                public String name() { return name; }
                public int currentValue() { return value; }
                public int minValue() { return rule.minValue(); }
                public int maxValue() { return rule.maxValue(); }
                public void apply(int v) { throw new AssertionError("A controller must never apply actions itself"); }
            });
        }
        return result;
    }

    /** A baseline that proposes what the recorded run proposed in the same window. */
    static Controller recorded(AtomicInteger calls, java.util.concurrent.atomic.AtomicReference<JsonNode> window) {
        return (state, actuators) -> {
            calls.incrementAndGet();
            ControlAction.Builder action = ControlAction.builder();
            window.get().get("intent").fields().forEachRemaining(e -> action.set(e.getKey(), e.getValue().asInt(), "recorded"));
            return action.build();
        };
    }

    static Controller baseline(AtomicInteger calls) {
        return (state, actuators) -> {
            calls.incrementAndGet();
            return ControlAction.builder().hold(actuators.get(0).name(), "baseline_test").build();
        };
    }

    @Test void decisionsMatchThePythonReferenceOnRecordedWindows() throws Exception {
        JsonNode fixture = fixture();
        PolicyModel model = PolicyModel.load(POLICY);
        List<ActuatorGuardrail> rules = rules(fixture);
        AtomicInteger baselineCalls = new AtomicInteger();
        var current = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        CoordinatedController controller = new CoordinatedController(model, rules, fixture.get("sloMs").asDouble(),
                recorded(baselineCalls, current), Long.MAX_VALUE);
        var space = new com.iasr.core.resource.CoordinatedActionSpace(model.resources().stream()
                .map(r -> new com.iasr.core.resource.CoordinatedActionSpace.Managed(r.signals(),
                        rules.stream().filter(g -> g.actuatorName().equals(r.signals().actuatorName())).findFirst().orElseThrow(),
                        r.levels())).toList(), fixture.get("sloMs").asDouble());
        int decided = 0, deviated = 0, windows = 0;
        for (JsonNode window : fixture.get("windows")) {
            windows++;
            current.set(window);
            List<Actuator> actuators = actuators(model, rules, window);
            ControlAction action = controller.decide(snapshot(window), actuators);
            JsonNode expected = window.get("expected");
            String reason = action.reason(model.resources().get(0).signals().actuatorName());
            Map<String, Integer> intent = JSON.convertValue(window.get("intent"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            // Every decision carries the rules' proposal, so a log can always be replayed against it.
            assertThat(action.baselineValues()).contains(intent);
            if (expected == null) {
                assertThat(reason).startsWith("coordinated_fallback_");
                continue;
            }
            decided++;
            int[] reference = JSON.convertValue(expected.get("reference"), int[].class);
            assertThat(space.admissible(intent, snapshot(window), actuators)).containsExactly(reference);
            if ("baseline".equals(expected.get("reason").asText())) {
                assertThat(reason).startsWith("coordinated_baseline");
                assertThat(action.desiredValues()).isEqualTo(intent);
                continue;
            }
            deviated++;
            assertThat(reason).isEqualTo("coordinated_policy");
            for (int i = 0; i < model.resources().size(); i++) {
                assertThat(action.getOrDefault(model.resources().get(i).signals().actuatorName(), Integer.MIN_VALUE))
                        .isEqualTo(expected.get("targets").get(i).asInt());
            }
        }
        assertThat(baselineCalls.get()).isEqualTo(windows);
        assertThat(decided).isGreaterThan(10);
        assertThat(deviated).isGreaterThan(0);
        assertThat(decided - deviated).isGreaterThan(0);
        assertThat(controller.statistics().get("modelDecisions")).isEqualTo((long) decided);
        assertThat(controller.statistics().get("deviationsFromBaseline")).isEqualTo((long) deviated);
    }

    @Test void featuresAndValuesMatchThePythonReference() throws Exception {
        JsonNode fixture = fixture();
        PolicyModel model = PolicyModel.load(POLICY);
        List<SoftResource> signals = model.resources().stream().map(PolicyModel.Resource::signals).toList();
        PolicyFeatures features = new PolicyFeatures(signals, fixture.get("sloMs").asDouble(), model.smoothing(), model.ratioCap());
        PolicyModel.Deadline never = new PolicyModel.Deadline(Long.MAX_VALUE / 2, () -> 0L);
        int compared = 0;
        for (JsonNode window : fixture.get("windows")) {
            MetricsSnapshot state = snapshot(window);
            JsonNode expected = window.get("expected");
            if (!PolicyFeatures.isActive(state) || !state.has("observation_elapsed_sec")) { features.reset(); continue; }
            int[] capacities = JSON.convertValue(window.get("capacities"), int[].class);
            double[] actual = features.observe(state, capacities);
            if (expected == null) continue;
            double[] reference = JSON.convertValue(expected.get("features"), double[].class);
            assertThat(actual).containsExactly(reference);
            for (int c = 0; c < expected.get("candidates").size(); c++) {
                if (expected.get("values").get(c).isNull()) continue;
                int[] candidate = JSON.convertValue(expected.get("candidates").get(c), int[].class);
                assertThat(model.value(actual, features.action(capacities, candidate), never))
                        .isCloseTo(expected.get("values").get(c).asDouble(), within(1e-12));
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(30);
    }

    @Test void exceededBudgetFallsBackToTheBaseline() throws Exception {
        JsonNode fixture = fixture();
        PolicyModel model = PolicyModel.load(POLICY);
        List<ActuatorGuardrail> rules = rules(fixture);
        AtomicLong time = new AtomicLong();
        CoordinatedController controller = new CoordinatedController(model, rules, fixture.get("sloMs").asDouble(),
                baseline(new AtomicInteger()), 1_000, () -> time.addAndGet(600));
        int budget = 0;
        for (JsonNode window : fixture.get("windows")) {
            ControlAction action = controller.decide(snapshot(window), actuators(model, rules, window));
            String reason = action.reason(model.resources().get(0).signals().actuatorName());
            assertThat(reason).startsWith("coordinated_fallback_");
            if (reason.startsWith("coordinated_fallback_budget")) budget++;
        }
        assertThat(budget).isGreaterThan(10);
    }

    @Test void aSkippedTickRestartsTheHistory() throws Exception {
        JsonNode fixture = fixture();
        PolicyModel model = PolicyModel.load(POLICY);
        List<ActuatorGuardrail> rules = rules(fixture);
        CoordinatedController controller = new CoordinatedController(model, rules, fixture.get("sloMs").asDouble(),
                baseline(new AtomicInteger()), Long.MAX_VALUE);
        List<JsonNode> windows = new ArrayList<>();
        fixture.get("windows").forEach(windows::add);
        String name = model.resources().get(0).signals().actuatorName();
        for (int i = 1; i <= 6; i++) controller.decide(snapshot(windows.get(i)), actuators(model, rules, windows.get(i)));
        ControlAction afterGap = controller.decide(snapshot(windows.get(9)), actuators(model, rules, windows.get(9)));
        assertThat(afterGap.reason(name)).startsWith("coordinated_fallback_short_history");
    }

    @Test void aPolicyForAnUnguardedResourceIsRejected() throws Exception {
        PolicyModel model = PolicyModel.load(POLICY);
        List<ActuatorGuardrail> onlyLimit = rules(fixture()).subList(0, 1);
        assertThatThrownBy(() -> new CoordinatedController(model, onlyLimit, 150, baseline(new AtomicInteger()), 1_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no guardrail");
    }

    @Test void damagedModelsAreRejectedAtLoad() throws Exception {
        String text = Files.readString(POLICY);
        Path cyclic = Files.createTempFile("policy", ".json");
        JsonNode root = JSON.readTree(text);
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("forest").get(0).get("left")).set(0, JSON.getNodeFactory().numberNode(0));
        Files.writeString(cyclic, JSON.writeValueAsString(root));
        assertThatThrownBy(() -> PolicyModel.load(cyclic)).isInstanceOf(IllegalArgumentException.class);
        Path version = Files.createTempFile("policy", ".json");
        Files.writeString(version, text.replace("\"modelVersion\":3", "\"modelVersion\":4"));
        assertThatThrownBy(() -> PolicyModel.load(version)).isInstanceOf(IllegalArgumentException.class);
    }
}
