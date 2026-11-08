"""Portable feature/reward contract v1. No estimator, fit on train only."""
import math
import struct
from policy_data import LIMIT, POOL, METRICS, TICK
from validate_episodes import require

EXTRA = ("limit_fraction", "pool_fraction", "limit_absolute", "pool_absolute", "limit_cooldown",
         "pool_cooldown", "pool_phase", "elapsed_ratio", "window_seconds", "pool_period")
FEATURE_NAMES = [f"w{i}.{key}" for i in range(4) for key in (*METRICS, *EXTRA)]
FEATURE_NAMES += [f"missing.{name}" for name in FEATURE_NAMES.copy()]
LATENCIES = {"latency_p95_ms", "latency_p99_ms", "latency_mean_ms", "service_mean_ms",
             "admission_wait_mean_ms", "hikari_acquire_mean_ms", "gc_pause_p95_ms"}
REWARD = {"latency": 2.0, "timeout": 1.0, "error": 2.0, "movement": 0.05, "latencyCap": 10.0}


def number(value):
    require(type(value) in (int, float) and math.isfinite(value), "non-finite or non-numeric feature")
    return float(value)


def quantile(values, fraction):
    ordered = sorted(values)
    require(bool(ordered), "empty quantile")
    at = fraction * (len(ordered) - 1)
    low, high = math.floor(at), math.ceil(at)
    return ordered[low] + (ordered[high] - ordered[low]) * (at - low)


def raw_features(history, control, rref):
    require(len(history) == 4, "four observed windows required")
    slo = number(control["sloMs"])
    nominal = number(control["windowSec"])
    period = control["poolPeriodTicks"]
    require(slo > 0 and nominal > 0 and type(period) is int and period > 0 and rref > 0, "invalid context")
    features = []
    for observation in history:
        state, resources = observation["state"], observation["resourcesBefore"]
        limit, pool = number(resources[LIMIT]), number(resources[POOL])
        for metric in METRICS:
            value = state.get(metric)
            if value is not None:
                value = number(value)
                if metric in LATENCIES:
                    value /= slo
                elif metric == "goodput_rps":
                    value /= rref
                elif metric in ("inflight", "queue_length"):
                    value /= max(1, limit)
                elif metric in ("hikari_active", "hikari_idle", "hikari_pending"):
                    value /= max(1, pool)
            features.append(value)
        for name, current in ((LIMIT, limit), (POOL, pool)):
            rule = control["rules"][name]
            features.append((current - rule["minValue"]) / max(1, rule["maxValue"] - rule["minValue"]))
        features.extend((limit / 100.0, pool / 32.0))
        for name in (LIMIT, POOL):
            cooldown = observation["cooldownRemaining"].get(name)
            features.append(None if cooldown is None else number(cooldown) / max(1, control["rules"][name]["cooldownTicks"]))
        tick = number(state[TICK])
        require(tick >= 0 and tick == math.floor(tick), "invalid decision tick")
        features.extend(((int(tick) % period) / max(1, period - 1),
                         number(observation["elapsedSeconds"]) / nominal, nominal / 5.0, period / 5.0))
    require(len(features) * 2 == len(FEATURE_NAMES), "feature schema mismatch")
    return features


def fit_preprocessor(train):
    positive = [number(r["history"][-1]["state"]["goodput_rps"]) for r in train
                if r["history"][-1]["state"].get("goodput_rps") is not None
                and r["history"][-1]["state"]["goodput_rps"] > 0]
    require(bool(positive), "train contains no positive goodput")
    rref = max(1.0, quantile(positive, 0.95))
    raw = [raw_features(r["history"], r["context"], rref) for r in train]
    centers, scales = [], []
    for column in zip(*raw):
        values = [v for v in column if v is not None]
        centers.append(quantile(values, 0.5) if values else 0.0)
        scales.append(max(0.1, quantile(values, 0.75) - quantile(values, 0.25)) if values else 1.0)
    return {"featureVersion": 1, "featureNames": FEATURE_NAMES, "rref": rref, "centers": centers, "scales": scales,
            "fittedOn": "train_current_histories", "featureDtype": "float32"}


def float32(value):
    value = number(value)
    require(abs(value) < 1e30, "feature is outside numeric limits")
    return struct.unpack("f", struct.pack("f", value))[0]


def transform(history, control, preprocessing):
    require(preprocessing["featureVersion"] == 1 and preprocessing["featureNames"] == FEATURE_NAMES,
            "unsupported feature contract")
    raw = raw_features(history, control, preprocessing["rref"])
    require(len(raw) == len(preprocessing["centers"]) == len(preprocessing["scales"]), "invalid preprocessing dimensions")
    values = [(value - center) / scale if value is not None else 0.0
              for value, center, scale in zip(raw, preprocessing["centers"], preprocessing["scales"])]
    return [float32(v) for v in values] + [1.0 if v is None else 0.0 for v in raw]


def action_features(state_features, action, control):
    return [*state_features, float32(action[0] / control["limitStep"]), float32(action[1])]


def reward(record, preprocessing, weights=REWARD):
    outcome = record["nextHistory"][-1]["state"]
    gp, latency, errors, timeouts = (number(outcome.get(key)) for key in
                                   ("goodput_rps", "latency_p95_ms", "error_rate", "timeout_rate"))
    require(gp >= 0 and latency >= 0 and 0 <= errors <= 1 and 0 <= timeouts <= 1, "invalid reward metrics")
    require(gp > 0 or errors > 0 or timeouts > 0, "no outcomes to score")
    require(latency > 0 or gp == 0, "successful outcomes without latency are unavailable")
    movement = 0.0
    for name, delta in zip((LIMIT, POOL), record["action"]):
        rule = record["context"]["rules"][name]
        width = rule["maxValue"] - rule["minValue"]
        require(width > 0 or delta == 0, "pinned resource changed")
        if width > 0:
            movement += abs(delta) / width
    return (gp / preprocessing["rref"]
            - weights["latency"] * min(weights["latencyCap"], max(0.0, latency / record["context"]["sloMs"] - 1))
            - weights["timeout"] * timeouts - weights["error"] * errors - weights["movement"] * movement)
