package com.iasr.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iasr.core.controller.JointActionSpace;
import com.iasr.core.guardrail.ActuatorGuardrail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FqiModelTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path ROOT = root();
    static final Path MODEL = Path.of(System.getProperty("iasr.test.model",
            ROOT.resolve("docs/benchmark/models/fqi-prototype-2026-11-08/model.json").toString()));
    @TempDir Path temporary;

    static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(path.resolve("docs/benchmark"))) path = path.getParent();
        return path;
    }
    static InferenceBudget unlimited() { return new InferenceBudget(Long.MAX_VALUE); }
    static JsonNode fixtures() throws Exception {
        String path = System.getProperty("iasr.test.fixtures");
        return path == null ? JSON.readTree(FqiModelTest.class.getResourceAsStream("/fqi-parity.json"))
                : JSON.readTree(Path.of(path).toFile());
    }
    static PolicyContext context(JsonNode node) throws Exception {
        Map<String, ActuatorGuardrail> rules = new LinkedHashMap<>();
        var entries = node.get("rules").fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            rules.put(entry.getKey(), JSON.treeToValue(entry.getValue(), ActuatorGuardrail.class));
        }
        return new PolicyContext(node.get("sloMs").asDouble(), node.get("windowSec").asDouble(),
                node.get("poolPeriodTicks").asInt(), rules);
    }
    static List<FqiFeatures.Observation> history(JsonNode rows) {
        List<FqiFeatures.Observation> result = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Double> state = new LinkedHashMap<>();
            row.get("state").fields().forEachRemaining(e -> state.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asDouble()));
            Map<String, Integer> resources = new LinkedHashMap<>(), cooldown = new LinkedHashMap<>();
            row.get("resourcesBefore").fields().forEachRemaining(e -> resources.put(e.getKey(), e.getValue().asInt()));
            row.get("cooldownRemaining").fields().forEachRemaining(e -> cooldown.put(e.getKey(), e.getValue().asInt()));
            result.add(new FqiFeatures.Observation(state, resources, cooldown, row.get("elapsedSeconds").asDouble()));
        }
        return result;
    }

    @Test void matchesPythonFeaturesMasksSupportAndDecisions() throws Exception {
        FqiModel model = FqiModel.loadPrototype(MODEL);
        int proposed = 0, fallback = 0, poolCandidates = 0;
        for (JsonNode test : fixtures()) {
            var history = history(test.get("history")); var context = context(test.get("context"));
            double[] expected = JSON.treeToValue(test.get("features"), double[].class);
            assertThat(model.features(history, context, unlimited())).containsExactly(expected);
            var legal = context.candidates(history.get(3));
            List<JointActionSpace.Delta> mask = new ArrayList<>();
            for (JsonNode action : test.get("candidates")) mask.add(new JointActionSpace.Delta(action.get(0).asInt(), action.get(1).asInt()));
            assertThat(legal).containsExactlyElementsOf(mask);
            if (legal.stream().anyMatch(d -> d.pool() != 0)) poolCandidates++;
            var actual = model.decide(history, context, unlimited()); var decision = test.get("decision");
            assertThat(actual.fallback()).isEqualTo(decision.get("fallback").asBoolean());
            if (actual.fallback()) { fallback++; continue; }
            proposed++;
            assertThat(actual.action()).isEqualTo(new JointActionSpace.Delta(decision.get("action").get(0).asInt(), decision.get("action").get(1).asInt()));
            assertThat(actual.qValue()).isCloseTo(decision.get("qValue").asDouble(), within(1e-12));
            assertThat(actual.supportedCandidates()).isEqualTo(decision.get("supportedCandidates").asInt());
        }
        assertThat(proposed).isPositive(); assertThat(fallback).isPositive(); assertThat(poolCandidates).isPositive();
    }

    @Test void matchesExportedSklearnPredictions() throws Exception {
        FqiModel model = FqiModel.loadPrototype(MODEL);
        for (JsonNode row : JSON.readTree(MODEL.resolveSibling("prediction-fixtures.json").toFile())) {
            assertThat(model.predict(JSON.treeToValue(row.get("features"), double[].class), unlimited()))
                    .isCloseTo(row.get("prediction").asDouble(), within(1e-12));
        }
        JsonNode artifact = JSON.readTree(MODEL.toFile());
        int count = artifact.has("forest") ? artifact.get("forest").size() : 0;
        if (artifact.has("actionForests")) for (JsonNode forest : artifact.get("actionForests")) count += forest.size();
        assertThat(model.treeCount()).isEqualTo(count);
        assertThat(model.sha256()).hasSize(64);
    }

    static JsonNode firstTree(JsonNode root) {
        return root.has("forest") ? root.get("forest").get(0) : root.get("actionForests").elements().next().get(0);
    }

    @Test void rejectsMalformedOrIncompatibleModel() throws Exception {
        for (String corruption : List.of("version", "scale", "features", "cycle", "split", "vector")) {
            ObjectNode root = (ObjectNode) JSON.readTree(MODEL.toFile());
            switch (corruption) {
                case "version" -> root.put("modelVersion", 99);
                case "scale" -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("preprocessing").get("scales")).set(0, JSON.getNodeFactory().numberNode(0));
                case "features" -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("preprocessing").get("featureNames")).set(0, JSON.getNodeFactory().textNode("wrong"));
                case "cycle" -> ((com.fasterxml.jackson.databind.node.ArrayNode) firstTree(root).get("left")).set(0, JSON.getNodeFactory().numberNode(0));
                case "split" -> ((com.fasterxml.jackson.databind.node.ArrayNode) firstTree(root).get("feature")).set(0, JSON.getNodeFactory().numberNode(210));
                case "vector" -> {
                    JsonNode support = root.path("support").path("bins").elements().next().elements().next();
                    ((com.fasterxml.jackson.databind.node.ArrayNode) support.path("vectors").get(0)).remove(0);
                }
            }
            Path path = temporary.resolve(corruption + ".json"); JSON.writeValue(path.toFile(), root);
            assertThatThrownBy(() -> FqiModel.loadPrototype(path)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsDuplicateKeysTrailingTokensAndNonFiniteInput() throws Exception {
        Path path = temporary.resolve("bad.json");
        Files.writeString(path, "{\"modelVersion\":1,\"modelVersion\":2}");
        assertThatThrownBy(() -> FqiModel.loadPrototype(path)).isInstanceOf(java.io.IOException.class);
        Files.writeString(path, Files.readString(MODEL) + " {}");
        assertThatThrownBy(() -> FqiModel.loadPrototype(path)).isInstanceOf(java.io.IOException.class);
        double[] input = new double[210]; input[0] = Double.NaN;
        assertThatThrownBy(() -> FqiModel.loadPrototype(MODEL).predict(input, unlimited())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scoresPoolActionFeatureWithFloat32SplitSemantics() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(MODEL.toFile());
        root.put("modelVersion", 1).put("algorithm", "masked_fqi_extra_trees");
        root.remove("actionForests");
        root.set("forest", JSON.readTree("[{\"left\":[1,-1,-1],\"right\":[2,-1,-1],\"feature\":[209,-2,-2],"
                + "\"threshold\":[0.1,-2,-2],\"value\":[0,5,7]}]"));
        Path path = temporary.resolve("pool-action.json"); JSON.writeValue(path.toFile(), root);
        FqiModel model = FqiModel.loadPrototype(path);
        double[] input = new double[210];
        input[209] = -1;
        assertThat(model.predict(input, unlimited())).isEqualTo(5);
        input[209] = 1;
        assertThat(model.predict(input, unlimited())).isEqualTo(7);
        input[209] = 0.1; // float32(0.1) is greater than the double threshold.
        assertThat(model.predict(input, unlimited())).isEqualTo(7);
    }

    private ObjectNode routedPrototype() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(MODEL.toFile());
        root.put("modelVersion", 2).put("algorithm", "masked_fqi_action_forests").put("stateFeatureCount", 208);
        root.remove("forest");
        var actions = root.putObject("actionForests");
        for (int dl = -1; dl <= 1; dl++) for (int dp = -1; dp <= 1; dp++) {
            actions.set(dl + "," + dp, JSON.readTree("[{\"left\":[-1],\"right\":[-1],\"feature\":[-2],"
                    + "\"threshold\":[-2],\"value\":[" + (10 * dl + dp) + "]}]"));
        }
        return root;
    }

    @Test void routesAllJointActionsAndRejectsUndefinedOrFractionalActions() throws Exception {
        ObjectNode root = routedPrototype();
        Path path = temporary.resolve("routed.json"); JSON.writeValue(path.toFile(), root);
        FqiModel model = FqiModel.loadPrototype(path);
        assertThat(model.modelVersion()).isEqualTo(2);
        assertThat(model.treeCount()).isEqualTo(9);
        double[] features = new double[210];
        for (int dl = -1; dl <= 1; dl++) for (int dp = -1; dp <= 1; dp++) {
            features[208] = dl; features[209] = dp;
            assertThat(model.predict(features, unlimited())).isEqualTo(10 * dl + dp);
        }
        features[208] = 0.1;
        assertThatThrownBy(() -> model.predict(features, unlimited())).isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) root.get("actionForests")).remove("1,1");
        root.path("support").path("bins").forEach(bucket -> ((ObjectNode) bucket).remove("1,1"));
        JSON.writeValue(path.toFile(), root);
        FqiModel sparse = FqiModel.loadPrototype(path);
        features[208] = 1; features[209] = 1;
        assertThatThrownBy(() -> sparse.predict(features, unlimited())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMissingSupportForestActionFeaturesInStateTreesAndAmbiguousSchemas() throws Exception {
        for (String corruption : List.of("missing", "state", "ambiguous", "key", "size")) {
            ObjectNode root = routedPrototype();
            ObjectNode actions = (ObjectNode) root.get("actionForests");
            switch (corruption) {
                case "missing" -> actions.remove("0,0");
                case "state" -> actions.set("0,0", JSON.readTree("[{\"left\":[1,-1,-1],\"right\":[2,-1,-1],"
                        + "\"feature\":[208,-2,-2],\"threshold\":[0,-2,-2],\"value\":[0,1,2]}]"));
                case "ambiguous" -> root.set("forest", actions.get("0,0").deepCopy());
                case "key" -> { actions.remove("1,1"); actions.set("2,0", actions.get("0,0").deepCopy()); }
                case "size" -> {
                    var trees = JSON.createArrayNode();
                    for (int i = 0; i < 257; i++) trees.add(actions.get("0,0").get(0).deepCopy());
                    actions.set("0,0", trees);
                }
            }
            Path path = temporary.resolve(corruption + "-routed.json"); JSON.writeValue(path.toFile(), root);
            assertThatThrownBy(() -> FqiModel.loadPrototype(path)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void cooperativeDeadlineStopsScoring() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong();
        var budget = new InferenceBudget(5, time::getAndIncrement);
        assertThatThrownBy(() -> FqiModel.loadPrototype(MODEL).predict(new double[210], budget)).isInstanceOf(InferenceBudget.Exceeded.class);
    }
}
