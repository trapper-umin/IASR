package com.iasr.policy.coordinated;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iasr.core.resource.ActionLevel;
import com.iasr.core.resource.SoftResource;

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

/**
 * A learned coordinated policy loaded from plain JSON: the resources it
 * manages, the moves it may make, the trees of its action-value function and
 * the table of state kinds in which each move was actually tried.
 * <p>
 * The file is data only. Loading validates every bound and every tree edge, so
 * a damaged or hostile file is rejected instead of looping or indexing out of
 * range inside the control loop.
 */
public final class PolicyModel {
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    /** A managed resource as the model was trained on it. */
    public record Resource(SoftResource signals, List<ActionLevel> levels) { }
    private record Tree(int[] left, int[] right, int[] feature, double[] threshold, double[] value) { }
    /** Outcome of scoring; {@code targets} is null when no candidate has support. */
    public record Choice(int[] targets, double value, int supported, String reason) { }

    private final List<Resource> resources;
    private final double smoothing, ratioCap, margin, confidence;
    private final int minHistory, minSupport, stateSize, actionSize;
    private final Map<String, Map<String, Integer>> support;
    private final List<Tree> forest;
    /** Trees of V(s) when the model is stored as V(s) + A(s, a); empty for a single regressor. */
    private final List<Tree> valueForest;
    private final String sha256;

    private PolicyModel(JsonNode root, String digest) {
        require(integer(root.get("modelVersion")) == 3 && "fqi_extra_trees_afterstate".equals(text(root.get("algorithm")))
                && integer(root.get("featureVersion")) == PolicyFeatures.VERSION, "Unsupported policy model");
        smoothing = numeric(root.get("smoothing"));
        ratioCap = numeric(root.get("ratioCap"));
        margin = numeric(root.get("margin"));
        confidence = root.has("confidence") ? numeric(root.get("confidence")) : 0.0;
        require(confidence >= 0, "Invalid confidence factor");
        minHistory = integer(root.get("minHistory"));
        require(smoothing > 0 && smoothing <= 1 && ratioCap > 0 && margin >= 0 && minHistory >= 1, "Invalid policy parameters");
        List<Resource> parsed = new ArrayList<>();
        JsonNode list = root.path("resources");
        require(list.isArray() && !list.isEmpty() && list.size() <= 8, "Invalid resource list");
        for (JsonNode node : list) {
            List<ActionLevel> levels = new ArrayList<>();
            require(node.path("levels").isArray() && node.get("levels").size() <= 16, "Invalid action levels");
            for (JsonNode level : node.get("levels")) {
                require(level.isArray() && level.size() == 2, "Invalid action level");
                levels.add(new ActionLevel(numeric(level.get(0)), integer(level.get(1))));
            }
            parsed.add(new Resource(new SoftResource(text(node.get("actuator")), text(node.get("usage")),
                    text(node.get("backlog")), text(node.get("wait")), text(node.get("hold"))), List.copyOf(levels)));
        }
        resources = List.copyOf(parsed);
        stateSize = PolicyFeatures.stateSize(resources.size());
        actionSize = PolicyFeatures.actionSize(resources.size());
        require(root.path("stateFeatures").size() == stateSize && root.path("actionFeatures").size() == actionSize,
                "Feature contract does not match the resources");
        JsonNode supportNode = root.path("support");
        minSupport = integer(supportNode.get("minSamples"));
        require(minSupport >= 1 && supportNode.path("bins").isObject() && supportNode.get("bins").size() <= 4096, "Invalid support table");
        Map<String, Map<String, Integer>> bins = new HashMap<>();
        var binFields = supportNode.get("bins").fields();
        while (binFields.hasNext()) {
            var bin = binFields.next();
            require(bin.getValue().isObject() && bin.getValue().size() <= 6561, "Invalid support bin");
            Map<String, Integer> counts = new HashMap<>();
            var entries = bin.getValue().fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                int samples = integer(entry.getValue());
                require(samples >= 0, "Invalid support count");
                counts.put(entry.getKey(), samples);
            }
            bins.put(bin.getKey(), Map.copyOf(counts));
        }
        support = Map.copyOf(bins);
        long[] nodes = {0};
        forest = readForest(root.path("forest"), stateSize + actionSize, nodes);
        valueForest = root.has("valueForest") ? readForest(root.get("valueForest"), stateSize, nodes) : List.of();
        sha256 = digest;
    }

    public static PolicyModel load(Path path) throws IOException {
        require(Files.size(path) <= MAX_BYTES, "Policy exceeds byte limit");
        byte[] bytes = Files.readAllBytes(path);
        require(bytes.length <= MAX_BYTES, "Policy exceeds byte limit");
        ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode root = mapper.readTree(bytes);
        require(root != null && root.isObject(), "Policy must be a JSON object");
        try {
            return new PolicyModel(root, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Resource> resources() { return resources; }
    public double smoothing() { return smoothing; }
    public double ratioCap() { return ratioCap; }
    public int minHistory() { return minHistory; }
    public String sha256() { return sha256; }
    public int treeCount() { return forest.size() + valueForest.size(); }

    /** Estimated value of taking an action in a state. */
    public double value(double[] state, double[] action, Deadline deadline) {
        double[] input = input(state, action);
        double base = valueForest.isEmpty() ? 0.0 : mean(valueForest, input, deadline);
        return base + mean(forest, input, deadline);
    }

    private static double mean(List<Tree> trees, double[] input, Deadline deadline) {
        double sum = 0;
        for (Tree tree : trees) {
            deadline.check();
            int node = 0;
            while (tree.left[node] != -1) {
                node = input[tree.feature[node]] <= tree.threshold[node] ? tree.left[node] : tree.right[node];
            }
            sum += tree.value[node];
        }
        return sum / trees.size();
    }

    private static List<Tree> readForest(JsonNode trees, int featureCount, long[] nodes) {
        require(trees.isArray() && !trees.isEmpty() && trees.size() <= 512, "Invalid forest size");
        List<Tree> loaded = new ArrayList<>();
        for (JsonNode tree : trees) {
            int size = tree.path("left").size();
            require(size > 0 && size <= 50_000, "Invalid tree size");
            nodes[0] += size;
            require(nodes[0] <= 4_000_000, "Too many tree nodes");
            Tree value = new Tree(ints(tree.get("left"), size), ints(tree.get("right"), size),
                    ints(tree.get("feature"), size), doubles(tree.get("threshold"), size), doubles(tree.get("value"), size));
            validate(value, featureCount);
            loaded.add(value);
        }
        return List.copyOf(loaded);
    }

    /**
     * Decide relative to a reference configuration: the one the rule-based
     * controller is about to apply.
     * <p>
     * Every supported candidate is scored by the lower confidence bound of its
     * advantage over the reference, computed tree by tree: mean − κ·sd of the
     * per-tree differences. The state value cancels in the difference, and the
     * spread says how much the ensemble disagrees about this particular
     * deviation. The policy departs from the reference only when the best bound
     * exceeds the margin; otherwise the reference stands. This is safe policy
     * improvement with baseline bootstrapping: where the data do not support a
     * confident judgement, the behaviour is the baseline's. Among equal bounds
     * the candidate closer to the reference wins.
     */
    public Choice choose(PolicyFeatures features, double[] state, String bin, int[] capacities,
                         List<int[]> candidates, int[] reference, Deadline deadline) {
        Map<String, Integer> tried = support.getOrDefault(bin, Map.of());
        double[] base = trees(forest, input(state, features.action(capacities, reference)), deadline);
        int[] best = null;
        double bestScore = 0;
        int bestDistance = 0, supported = 0;
        for (int[] candidate : candidates) {
            if (java.util.Arrays.equals(candidate, reference)) continue;
            if (tried.getOrDefault(PolicyFeatures.direction(capacities, candidate), 0) < minSupport) continue;
            supported++;
            int distance = 0;
            for (int r = 0; r < capacities.length; r++) distance += Math.abs(candidate[r] - reference[r]);
            double score = lowerBound(trees(forest, input(state, features.action(capacities, candidate)), deadline), base);
            if (best == null || score > bestScore || (score == bestScore && distance < bestDistance)) {
                best = candidate; bestScore = score; bestDistance = distance;
            }
        }
        if (best == null || bestScore <= margin) {
            return new Choice(reference.clone(), value(state, features.action(capacities, reference), deadline), supported, "baseline");
        }
        return new Choice(best, value(state, features.action(capacities, best), deadline), supported, "policy");
    }

    private double lowerBound(double[] move, double[] hold) {
        int n = move.length;
        double[] d = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) { d[i] = move[i] - hold[i]; sum += d[i]; }
        double mean = sum / n;
        double squares = 0;
        for (int i = 0; i < n; i++) squares += (d[i] - mean) * (d[i] - mean);
        double variance = n > 1 ? squares / (n - 1) : 0.0;
        return mean - confidence * Math.pow(variance, 0.5);
    }

    private double[] input(double[] state, double[] action) {
        require(state.length == stateSize && action.length == actionSize, "Feature vector has the wrong size");
        double[] input = new double[stateSize + actionSize];
        for (int i = 0; i < input.length; i++) {
            double raw = i < stateSize ? state[i] : action[i - stateSize];
            require(Double.isFinite(raw), "Non-finite feature");
            input[i] = (float) raw;
        }
        return input;
    }

    private static double[] trees(List<Tree> trees, double[] input, Deadline deadline) {
        double[] values = new double[trees.size()];
        for (int t = 0; t < values.length; t++) {
            deadline.check();
            Tree tree = trees.get(t);
            int node = 0;
            while (tree.left[node] != -1) {
                node = input[tree.feature[node]] <= tree.threshold[node] ? tree.left[node] : tree.right[node];
            }
            values[t] = tree.value[node];
        }
        return values;
    }

    /** Cooperative time limit checked between trees. */
    public static final class Deadline {
        public static final class Exceeded extends RuntimeException {
            Exceeded() { super("Inference budget exceeded", null, false, false); }
        }
        private final long deadlineNanos;
        private final java.util.function.LongSupplier clock;
        public Deadline(long budgetNanos, java.util.function.LongSupplier clock) {
            this.clock = clock;
            this.deadlineNanos = clock.getAsLong() + budgetNanos;
        }
        void check() { if (clock.getAsLong() - deadlineNanos > 0) throw new Exceeded(); }
    }

    private static void validate(Tree tree, int featureCount) {
        int[] parents = new int[tree.left.length];
        for (int i = 0; i < tree.left.length; i++) {
            if (tree.left[i] == -1) {
                require(tree.right[i] == -1, "Invalid leaf");
            } else {
                require(tree.feature[i] >= 0 && tree.feature[i] < featureCount, "Invalid split feature");
                for (int child : new int[]{tree.left[i], tree.right[i]}) {
                    require(child > i && child < tree.left.length, "Cyclic or invalid tree edge");
                    require(++parents[child] == 1, "Shared tree child");
                }
            }
            require(i == 0 || parents[i] == 1, "Unreachable tree node");
            require(Double.isFinite(tree.value[i]) && (tree.left[i] == -1 || Double.isFinite(tree.threshold[i])), "Non-finite tree value");
        }
    }

    private static String text(JsonNode node) { require(node != null && node.isTextual(), "Expected string"); return node.textValue(); }
    private static int integer(JsonNode node) { require(node != null && node.isIntegralNumber() && node.canConvertToInt(), "Expected integer"); return node.intValue(); }
    private static double numeric(JsonNode node) {
        require(node != null && node.isNumber() && Double.isFinite(node.doubleValue()), "Expected finite number");
        return node.doubleValue();
    }
    private static double[] doubles(JsonNode node, int size) {
        require(node != null && node.isArray() && node.size() == size, "Invalid numeric array");
        double[] values = new double[size];
        for (int i = 0; i < size; i++) { require(node.get(i).isNumber(), "Expected number"); values[i] = node.get(i).doubleValue(); }
        return values;
    }
    private static int[] ints(JsonNode node, int size) {
        require(node != null && node.isArray() && node.size() == size, "Invalid integer array");
        int[] values = new int[size];
        for (int i = 0; i < size; i++) values[i] = integer(node.get(i));
        return values;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
