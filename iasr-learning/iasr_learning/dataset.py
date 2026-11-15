"""Turn recorded benchmark episodes into transitions for fitted Q iteration."""
import json
import math
from pathlib import Path

from . import features as F

DEFAULT_RESOURCES = [
    {**F.CONCURRENCY, "levels": [[-0.25, 2], [-0.1, 1], [0, 0], [0.1, 1], [0.25, 2]]},
    {**F.CONNECTION_POOL, "levels": [[-0.15, 1], [0, 0], [0.15, 1]]},
]
MOVE_COST = 0.02
MIN_HISTORY = 2


def read_episode(directory):
    directory = Path(directory)
    metadata = json.loads((directory / "metadata.json").read_text())
    rows = [json.loads(line) for line in (directory / "dataset.jsonl").read_text().splitlines() if line.strip()]
    return metadata, rows


def find_episodes(root):
    return sorted(path.parent for path in Path(root).rglob("dataset.jsonl"))


def regular(row):
    """True when the window that produced this state had the configured length."""
    state = row["state"]
    window = F.number(state.get("control_window_sec"), math.nan)
    elapsed = F.number(state.get("observation_elapsed_sec"), math.nan)
    return (math.isfinite(window) and math.isfinite(elapsed) and window > 0
            and abs(elapsed - window) <= max(0.1, 0.25 * window))


def reward(next_state, capacities, chosen):
    """Share of offered load served within the objective, less a small price for moving."""
    moved = sum(abs(t - c) / max(1, c) for c, t in zip(capacities, chosen))
    return F.served_within_slo(next_state) - MOVE_COST * moved


def episode_rules(metadata, resources):
    by_name = {rule["actuatorName"]: rule for rule in metadata["configuration"]["guardrails"]}
    return [by_name[resource["actuator"]] for resource in resources]


def decision_points(metadata, rows, resources=DEFAULT_RESOURCES):
    """Yield every window at which the policy could decide, with its features and candidates.

    The smoother follows consecutive active windows exactly as the JVM
    controller does; an idle or irregular window resets it.
    """
    slo = float(metadata["configuration"]["sloMs"])
    rules = episode_rules(metadata, resources)
    levels = [resource["levels"] for resource in resources]
    smoother = F.Smoother(resources, slo)
    previous_tick = None
    for index, row in enumerate(rows):
        state = row["state"]
        tick = F.number(state.get("control_tick"), math.nan)
        names = [resource["actuator"] for resource in resources]
        if (not regular(row) or not F.is_active(state) or any(n not in row["resourcesBefore"] for n in names)):
            smoother.reset()
            previous_tick = None
            continue
        if previous_tick is None or tick != previous_tick + 1:
            smoother.reset()
        previous_tick = tick
        capacities = [int(row["resourcesBefore"][n]) for n in names]
        x_state = smoother.observe(state, capacities)
        if smoother.count < MIN_HISTORY:
            continue
        cooldowns = [F.number(row["cooldownRemaining"].get(n), math.nan) for n in names]
        options = F.candidates(rules, levels, capacities, cooldowns, F.number(state.get("latency_p95_ms"), math.nan), slo)
        chosen = [int(row["action"][n]) for n in names]
        intent = row.get("intent") or {}
        p95 = F.number(state.get("latency_p95_ms"), math.nan)
        reference = [F.clamp(rule, c, int(intent.get(n, c)), cd, p95, slo)
                     for rule, c, n, cd in zip(rules, capacities, names, cooldowns)]
        yield {"reference": reference, "referenceFeatures": smoother.action_features(capacities, reference),"index": index, "tick": tick, "state": x_state, "capacities": capacities,
               "candidates": options, "candidateFeatures": [smoother.action_features(capacities, o) for o in options],
               "chosen": chosen, "chosenFeatures": smoother.action_features(capacities, chosen),
               "bin": F.support_bin(state, resources, slo), "raw": state}


def transitions(metadata, rows, resources=DEFAULT_RESOURCES):
    """Pairs of consecutive decision points: (s, a, r, s') with the candidates available in s'."""
    points = list(decision_points(metadata, rows, resources))
    result = []
    for current, following in zip(points, points[1:]):
        if following["index"] != current["index"] + 1:
            continue
        elapsed = F.number(following["raw"].get("observation_elapsed_sec"), 1.0)
        next_candidates = list(following["candidateFeatures"])
        next_directions = [F.direction(following["capacities"], o) for o in following["candidates"]]
        if following["reference"] not in following["candidates"]:
            # The proposal of the rules was available too; it needs no support.
            next_candidates.append(following["referenceFeatures"])
            next_directions.append("*")
        result.append({"state": current["state"], "action": current["chosenFeatures"],
                       "reward": reward(following["raw"], current["capacities"], current["chosen"]),
                       "elapsed": elapsed, "nextState": following["state"],
                       "nextCandidates": next_candidates, "nextDirections": next_directions,
                       "nextBin": following["bin"], "bin": current["bin"],
                       "direction": F.direction(current["capacities"], current["chosen"])})
    return result


def load(root, resources=DEFAULT_RESOURCES):
    """All transitions below a directory, each tagged with its episode."""
    result = []
    for directory in find_episodes(root):
        metadata, rows = read_episode(directory)
        for item in transitions(metadata, rows, resources):
            item["episode"] = metadata.get("episodeId", str(directory))
            item["scenario"] = metadata.get("scenario")
            item["mode"] = metadata.get("mode")
            result.append(item)
    return result
