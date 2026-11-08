package com.iasr.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iasr.core.controller.JointActionSpace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Immutable, bounded, data-only model loader and scorer. No class deserialization or background inference. */
public final class FqiModel {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final int modelVersion;
    private final String algorithm;
    private final double rref;
    private final double[] centers, scales;
    private final Map<String, Map<String, Support>> bins;
    private final List<Tree> forest;
    private final Map<String, List<Tree>> actionForests;
    private final int minSamples, minEpisodes;
    private final double maxDistance;
    private final String sha256;

    private record Support(int samples, int episodes, List<double[]> vectors) { }
    private record Tree(int[] left, int[] right, int[] feature, double[] threshold, double[] value) { }
    public record Decision(JointActionSpace.Delta action, double qValue, int supportedCandidates) {
        public boolean fallback() { return action == null; }
    }

    private FqiModel(JsonNode root, String digest) {
        modelVersion = integer(root.get("modelVersion"));
        algorithm = text(root.get("algorithm"));
        require((modelVersion == 1 && "masked_fqi_extra_trees".equals(algorithm))
                || (modelVersion == 2 && "masked_fqi_action_forests".equals(algorithm)), "Unsupported model");
        require(root.has("deploymentAllowed") && root.get("deploymentAllowed").isBoolean()
                && !root.get("deploymentAllowed").booleanValue(), "Expected explicitly non-deployable prototype");
        JsonNode p = root.path("preprocessing");
        require(integer(p.get("featureVersion")) == 1 && "float32".equals(text(p.get("featureDtype"))), "Unsupported feature contract");
        List<String> names = new ArrayList<>();
        require(p.path("featureNames").isArray(), "Missing feature order");
        p.get("featureNames").forEach(n -> names.add(text(n)));
        require(names.equals(FqiFeatures.names()), "Feature order mismatch");
        rref = numeric(p.get("rref")); require(rref > 0, "Invalid Rref");
        centers = doubles(p.get("centers"), 104); scales = doubles(p.get("scales"), 104);
        for (double scale : scales) require(scale > 0, "Invalid normalization scale");
        JsonNode support = root.path("support");
        minSamples = integer(support.get("minSamples")); minEpisodes = integer(support.get("minEpisodes"));
        maxDistance = numeric(support.get("maxRmsDistance"));
        require(minSamples > 0 && minEpisodes > 0 && maxDistance > 0, "Invalid support thresholds");
        JsonNode sourceBins = support.path("bins");
        require(sourceBins.isObject() && sourceBins.size() <= 256, "Invalid support bins");
        Map<String, Map<String, Support>> parsedBins = new HashMap<>();
        int vectors = 0;
        var binFields = sourceBins.fields();
        while (binFields.hasNext()) {
            var bin = binFields.next();
            require(bin.getKey().length() < 256 && bin.getValue().isObject() && bin.getValue().size() <= 9, "Invalid support bucket");
            Map<String, Support> actions = new HashMap<>();
            var fields = bin.getValue().fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                validateActionKey(entry.getKey());
                int samples = integer(entry.getValue().get("samples"));
                int episodes = integer(entry.getValue().get("episodes"));
                JsonNode matrix = entry.getValue().path("vectors");
                require(samples >= 0 && episodes >= 0 && episodes <= samples && matrix.isArray() && matrix.size() == samples, "Invalid support count");
                vectors += samples; require(vectors <= 10_000, "Too many support vectors");
                List<double[]> references = new ArrayList<>();
                for (JsonNode row : matrix) references.add(doubles(row, 208));
                actions.put(entry.getKey(), new Support(samples, episodes, List.copyOf(references)));
            }
            parsedBins.put(bin.getKey(), Map.copyOf(actions));
        }
        bins = Map.copyOf(parsedBins);
        int[] totalNodes = {0};
        if (modelVersion == 1) {
            require(!root.has("actionForests"), "Ambiguous model representation");
            forest = readForest(root.path("forest"), 210, totalNodes);
            actionForests = Map.of();
        } else {
            require(integer(root.get("stateFeatureCount")) == 208 && !root.has("forest"), "Invalid action forest schema");
            JsonNode byAction = root.path("actionForests");
            require(byAction.isObject() && !byAction.isEmpty() && byAction.size() <= 9, "Invalid action forest count");
            Map<String, List<Tree>> parsed = new HashMap<>();
            var actions = byAction.fields();
            while (actions.hasNext()) {
                var action = actions.next();
                validateActionKey(action.getKey());
                int dl = Integer.parseInt(action.getKey().split(",")[0]);
                require(dl >= -1 && dl <= 1, "Action forests require normalized deltas");
                parsed.put(action.getKey(), readForest(action.getValue(), 208, totalNodes));
            }
            for (var bucket : bins.values()) for (var key : bucket.keySet()) {
                String[] parts = key.split(",");
                String normalized = Integer.signum(Integer.parseInt(parts[0])) + "," + parts[1];
                require(parsed.containsKey(normalized), "Support references an untrained action");
            }
            forest = List.of();
            actionForests = Map.copyOf(parsed);
        }
        sha256 = digest;
    }

    public static FqiModel loadPrototype(Path path) throws IOException {
        require(Files.size(path) <= MAX_BYTES, "Model exceeds byte limit");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        require(bytes.length <= MAX_BYTES, "Model exceeds byte limit");
        ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode root = mapper.readTree(bytes);
        require(root != null && root.isObject(), "Model must be an object");
        try {
            return new FqiModel(root, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public String sha256() { return sha256; }
    public int treeCount() { return forest.size() + actionForests.values().stream().mapToInt(List::size).sum(); }
    public int modelVersion() { return modelVersion; }
    public String algorithm() { return algorithm; }

    public double[] features(List<FqiFeatures.Observation> history, PolicyContext context, InferenceBudget budget) {
        return FqiFeatures.transform(history, context, rref, centers, scales, budget);
    }

    public Decision decide(List<FqiFeatures.Observation> history, PolicyContext context, InferenceBudget budget) {
        double[] vector = features(history, context, budget);
        Map<String, Support> bucket = bins.getOrDefault(bin(history.get(3), context), Map.of());
        List<JointActionSpace.Delta> supported = new ArrayList<>();
        for (var delta : context.candidates(history.get(3))) {
            budget.check();
            Support entry = bucket.get(delta.concurrency() + "," + delta.pool());
            if (entry == null || entry.samples < minSamples || entry.episodes < minEpisodes) continue;
            for (double[] reference : entry.vectors) {
                budget.check();
                double squared = 0;
                for (int i = 0; i < vector.length; i++) squared += (vector[i] - reference[i]) * (vector[i] - reference[i]);
                if (Math.sqrt(squared / vector.length) <= maxDistance) { supported.add(delta); break; }
            }
        }
        JointActionSpace.Delta best = null;
        double value = Double.NEGATIVE_INFINITY;
        int movement = Integer.MAX_VALUE;
        for (var delta : supported) {
            double score = predict(FqiFeatures.withAction(vector, delta, context), budget);
            int change = Math.abs(delta.concurrency()) + Math.abs(delta.pool());
            if (best == null || score > value || (score == value && change < movement)) {
                best = delta; value = score; movement = change;
            }
        }
        budget.check();
        return new Decision(best, best == null ? Double.NaN : value, supported.size());
    }

    public double predict(double[] features, InferenceBudget budget) {
        require(features.length == 210, "Forest input must have 210 features");
        double[] values = new double[features.length];
        for (int i = 0; i < values.length; i++) values[i] = FqiFeatures.f32(features[i]);
        List<Tree> evaluated = forest;
        if (modelVersion == 2) {
            double dl = values[208], dp = values[209];
            require(dl >= -1 && dl <= 1 && dl == Math.floor(dl) && dp >= -1 && dp <= 1 && dp == Math.floor(dp),
                    "Action coordinates must be normalized discrete deltas");
            evaluated = actionForests.get((int) dl + "," + (int) dp);
            require(evaluated != null, "Action has no trained forest");
        }
        double sum = 0;
        for (Tree tree : evaluated) {
            int node = 0;
            while (tree.left[node] != -1) {
                budget.check();
                node = values[tree.feature[node]] <= tree.threshold[node] ? tree.left[node] : tree.right[node];
            }
            sum += tree.value[node];
        }
        budget.check();
        return FqiFeatures.finite(sum / evaluated.size());
    }

    private static String bin(FqiFeatures.Observation observation, PolicyContext context) {
        var state = observation.state();
        Double latency = state.get("latency_p95_ms"), pending = state.get("hikari_pending"), inflight = state.get("inflight");
        String band = latency == null ? "missing" : latency / context.sloMs() < 0.7 ? "comfortable" : latency <= context.sloMs() ? "within_slo" : "over_slo";
        String pool = pending == null ? "missing" : pending > 0 ? "pending" : "no_pending";
        String busy = inflight == null ? "missing" : inflight >= 0.9 * Math.max(1, observation.resourcesBefore().get(JointActionSpace.LIMIT)) ? "binding" : "headroom";
        long tick = (long) FqiFeatures.required(state.get("control_tick"));
        return String.join("/", band, pool, busy, tick % context.poolPeriodTicks() == 0 ? "pool_tick" : "limit_tick");
    }

    private static List<Tree> readForest(JsonNode trees, int featureCount, int[] totalNodes) {
        require(trees.isArray() && !trees.isEmpty() && trees.size() <= 256, "Invalid forest size");
        List<Tree> result = new ArrayList<>();
        for (JsonNode tree : trees) {
            int size = tree.path("left").size();
            require(size > 0 && size <= 20_000, "Invalid tree size");
            totalNodes[0] += size;
            require(totalNodes[0] <= 200_000, "Too many tree nodes");
            Tree value = new Tree(ints(tree.get("left"), size), ints(tree.get("right"), size),
                    ints(tree.get("feature"), size), doubles(tree.get("threshold"), size), doubles(tree.get("value"), size));
            validateTree(value, featureCount);
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void validateTree(Tree tree, int featureCount) {
        int[] parents = new int[tree.left.length], depths = new int[tree.left.length];
        for (int i = 0; i < tree.left.length; i++) {
            if (tree.left[i] == -1) require(tree.right[i] == -1 && tree.feature[i] == -2, "Invalid leaf");
            else {
                require(tree.feature[i] >= 0 && tree.feature[i] < featureCount, "Invalid split feature");
                for (int child : new int[]{tree.left[i], tree.right[i]}) {
                    require(child > i && child < tree.left.length, "Cyclic or invalid tree edge");
                    require(++parents[child] == 1, "Shared tree child");
                    depths[child] = depths[i] + 1;
                    require(depths[child] <= 64, "Tree too deep");
                }
            }
            require(i == 0 || parents[i] == 1, "Unreachable tree node");
        }
    }

    private static void validateActionKey(String key) {
        String[] parts = key.split(",", -1);
        require(parts.length == 2, "Invalid action key");
        try {
            int dl = Integer.parseInt(parts[0]), dp = Integer.parseInt(parts[1]);
            require(dp >= -1 && dp <= 1 && (dl + "," + dp).equals(key), "Invalid action key");
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid action key", e); }
    }

    private static String text(JsonNode node) { require(node != null && node.isTextual(), "Expected string"); return node.textValue(); }
    private static int integer(JsonNode node) { require(node != null && node.isIntegralNumber() && node.canConvertToInt(), "Expected integer"); return node.intValue(); }
    private static double numeric(JsonNode node) { require(node != null && node.isNumber(), "Expected number"); return FqiFeatures.finite(node.doubleValue()); }
    private static double[] doubles(JsonNode node, int size) {
        require(node != null && node.isArray() && node.size() == size, "Invalid numeric array dimensions");
        double[] values = new double[size]; for (int i = 0; i < size; i++) values[i] = numeric(node.get(i)); return values;
    }
    private static int[] ints(JsonNode node, int size) {
        require(node != null && node.isArray() && node.size() == size, "Invalid integer array dimensions");
        int[] values = new int[size]; for (int i = 0; i < size; i++) values[i] = integer(node.get(i)); return values;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
