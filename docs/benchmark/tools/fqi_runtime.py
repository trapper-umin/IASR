"""Portable JSON forest scoring and empirical action support. No pickle or ML imports."""
import math
from fqi_features import transform, action_features, float32
from policy_data import candidates, state_bin
from validate_episodes import require, read_json


def action_key(action):
    return f"{action[0]},{action[1]}"


def bin_for(history, control):
    return state_bin({"history": history, "context": control})


def supported_actions(history, control, preprocessing, support, eligible=None):
    legal = candidates(history[-1], control)
    if eligible is not None:
        require(eligible == legal, "stored action mask differs from current contract")
    bucket = support["bins"].get(bin_for(history, control), {})
    vector = transform(history, control, preprocessing)
    result = []
    for action in legal:
        entry = bucket.get(action_key(action))
        if entry is None or entry["samples"] < support["minSamples"] or entry["episodes"] < support["minEpisodes"]:
            continue
        distance = min((math.sqrt(sum((a-b)**2 for a, b in zip(vector, reference)) / len(vector))
                        for reference in entry["vectors"]), default=math.inf)
        if distance <= support["maxRmsDistance"]:
            result.append(action)
    return result


def predict_forest(forest, features):
    require(bool(forest), "empty forest")
    values = [float32(value) for value in features]
    result = 0.0
    for tree in forest:
        node, steps = 0, 0
        while tree["left"][node] != -1:
            feature = tree["feature"][node]
            require(0 <= feature < len(values), "tree feature index out of range")
            node = tree["left"][node] if values[feature] <= tree["threshold"][node] else tree["right"][node]
            steps += 1
            require(0 <= node < len(tree["left"]) and steps <= len(tree["left"]), "invalid or cyclic tree")
        result += tree["value"][node]
    return result / len(forest)


def normalized_action_key(action):
    require(len(action) == 2, "action must have two coordinates")
    values = [float32(v) for v in action]
    require(all(v in (-1.0, 0.0, 1.0) for v in values), "action coordinates must be normalized discrete deltas")
    return f"{int(values[0])},{int(values[1])}"


def model_contract(model):
    require((model.get("modelVersion"), model.get("algorithm")) in
            ((1, "masked_fqi_extra_trees"), (2, "masked_fqi_action_forests")), "unsupported model")


def predict_model(model, features):
    model_contract(model)
    require(len(features) == 210, "model input must have 210 features")
    if model["modelVersion"] == 1:
        return predict_forest(model["forest"], features)
    key = normalized_action_key(features[-2:])
    require(key in model["actionForests"], "action has no trained forest")
    return predict_forest(model["actionForests"][key], features[:208])


def decide(model, history, control):
    model_contract(model)
    options = supported_actions(history, control, model["preprocessing"], model["support"])
    if not options:
        return {"action": None, "fallback": True, "reason": "no_empirical_support"}
    features = transform(history, control, model["preprocessing"])
    scored = [(predict_model(model, action_features(features, a, control)), a) for a in options]
    # Prefer smaller changes on ties; otherwise keep the deterministic action ordering.
    value, action = max(scored, key=lambda pair: (pair[0], -sum(abs(v) for v in pair[1])))
    return {"action": action, "qValue": value, "fallback": False, "supportedCandidates": len(options)}


def load_model(path):
    model = read_json(path)
    model_contract(model)
    require(model.get("deploymentAllowed") is False,
            "expected an explicitly non-deployable prototype model")
    if model["modelVersion"] == 1:
        require("actionForests" not in model, "ambiguous model representation")
    if model["modelVersion"] == 2:
        require(model.get("stateFeatureCount") == 208 and "forest" not in model, "invalid action forest schema")
        forests = model.get("actionForests")
        require(isinstance(forests, dict) and 0 < len(forests) <= 9, "invalid action forest count")
        for key, trees in forests.items():
            require(normalized_action_key([int(v) for v in key.split(",")]) == key and isinstance(trees, list) and bool(trees), "invalid action forest")
        for bucket in model["support"]["bins"].values():
            for key in bucket:
                dl, dp = [int(v) for v in key.split(",")]
                require(normalized_action_key(((dl > 0) - (dl < 0), dp)) in forests, "support references an untrained action")
    return model


def main():
    import argparse
    import json
    from pathlib import Path
    from collections import Counter
    parser = argparse.ArgumentParser(description="Inspect prototype decisions on observed histories; no policy performance estimate")
    parser.add_argument("model", type=Path)
    parser.add_argument("samples", type=Path)
    args = parser.parse_args()
    try:
        model = load_model(args.model)
        counts, fallbacks, total, examples = Counter(), 0, 0, []
        for line in args.samples.read_text().splitlines():
            row = json.loads(line)
            decision = decide(model, row["history"], row["context"])
            total += 1
            fallbacks += int(decision["fallback"])
            if decision["action"] is not None:
                counts[action_key(decision["action"])] += 1
            if len(examples) < 10:
                examples.append({"episodeId": row["episodeId"], "sequence": row["sequence"], **decision})
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Prototype scoring failed: {error}\n")
    print(json.dumps({"observedHistories": total, "fallbacks": fallbacks, "proposedActions": dict(counts),
                      "policyPerformanceEstablished": False, "examples": examples}, indent=2))


if __name__ == "__main__":
    main()
