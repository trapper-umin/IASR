"""Reference evaluation of an exported policy, independent of scikit-learn.

Mirrors ``com.iasr.policy.coordinated.PolicyModel``; the JVM parity fixtures
are produced here.
"""
import struct

from . import features as F


def f32(value):
    return struct.unpack("f", struct.pack("f", value))[0]


def predict_policy(policy, vector):
    """Q of one state-action vector under an exported policy."""
    value = 0.0
    if "valueForest" in policy:
        value = predict(policy["valueForest"], vector[:len(policy["stateFeatures"])])
    return value + predict(policy["forest"], vector)


def predict(forest, vector):
    values = [f32(v) for v in vector]
    total = 0.0
    for tree in forest:
        node = 0
        while tree["left"][node] != -1:
            node = tree["left"][node] if values[tree["feature"][node]] <= tree["threshold"][node] else tree["right"][node]
        total += tree["value"][node]
    return total / len(forest)


def tree_values(forest, vector):
    """Prediction of every tree, in tree order."""
    values = [f32(v) for v in vector]
    result = []
    for tree in forest:
        node = 0
        while tree["left"][node] != -1:
            node = tree["left"][node] if values[tree["feature"][node]] <= tree["threshold"][node] else tree["right"][node]
        result.append(tree["value"][node])
    return result


def improvement(policy, state, move, reference):
    """Lower confidence bound of the advantage of a move over the reference.

    The difference is taken tree by tree, so the state value cancels and the
    spread of the trees measures how much the ensemble disagrees about this
    particular deviation: mean − κ · standard deviation.
    """
    a = tree_values(policy["forest"], state + move)
    b = tree_values(policy["forest"], state + reference)
    d = [x - y for x, y in zip(a, b)]
    n = len(d)
    mean = sum(d) / n
    var = sum((x - mean) * (x - mean) for x in d) / (n - 1) if n > 1 else 0.0
    return mean - policy.get("confidence", 0.0) * var ** 0.5


def decide(policy, point):
    """Choose relative to the rules' proposal ``point["reference"]``.

    Returns ``(targets, q_values, reason)``. A supported candidate replaces the
    reference only when the lower confidence bound of its advantage exceeds the
    policy's margin; otherwise the reference stands (reason ``baseline``).
    """
    counts, minimum = policy["support"]["bins"], policy["support"]["minSamples"]
    reference = point.get("reference", point["capacities"])
    reference_features = point.get("referenceFeatures", point["candidateFeatures"][0])
    scored, best, best_score, best_distance = [], None, None, None
    for option, action in zip(point["candidates"], point["candidateFeatures"]):
        direction = F.direction(point["capacities"], option)
        if option == reference or counts.get(point["bin"], {}).get(direction, 0) < minimum:
            scored.append(None)
            continue
        scored.append(predict_policy(policy, point["state"] + action))
        score = improvement(policy, point["state"], action, reference_features)
        distance = sum(abs(t - r) for r, t in zip(reference, option))
        if best is None or score > best_score or (score == best_score and distance < best_distance):
            best, best_score, best_distance = option, score, distance
    if best is None or best_score <= policy["margin"]:
        return list(reference), scored, "baseline"
    return best, scored, "policy"
