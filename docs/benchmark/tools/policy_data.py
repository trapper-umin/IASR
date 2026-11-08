"""Version-1 joint action contract and four-window samples; no learned statistics."""
import math
from validate_episodes import timestamp, require

LIMIT, POOL = "concurrency_limit", "hikari_max_pool"
TICK = "control_tick"
WINDOW = "control_window_sec"
METRICS = ("latency_p95_ms", "latency_p99_ms", "latency_mean_ms", "service_mean_ms", "goodput_rps",
           "error_rate", "timeout_rate", "inflight", "queue_length", "admission_wait_mean_ms",
           "hikari_active", "hikari_idle", "hikari_pending", "hikari_acquire_mean_ms", "cpu_proc", "gc_pause_p95_ms")


def context(metadata):
    config = metadata["configuration"]
    rules = {r["actuatorName"]: r for r in config.get("guardrails", [])}
    require({LIMIT, POOL} <= rules.keys(), "joint policy requires two guardrails")
    nominal = config["iasr"]["windowMs"] / 1000
    require(math.isfinite(nominal) and nominal > 0, "invalid nominal window")
    slo = config["sloMs"]
    require(type(slo) in (int, float) and math.isfinite(slo) and slo > 0, "invalid SLO")
    exploration = config.get("exploration", {})
    require(exploration.get("actionSpaceVersion", 1) == 1, "unsupported action space")
    period = exploration.get("poolPeriodTicks", 5)
    require(type(period) is int and period >= 1, "invalid pool decision period")
    return {"actionSpaceVersion": 1, "sloMs": slo, "windowSec": nominal,
            "limitStep": max(1, math.floor(nominal + 0.5)), "poolPeriodTicks": period, "rules": rules}


def candidates(observation, control):
    """Mirror JointActionSpace v1. Only actual legal deltas are labels, never intent."""
    tick = observation["state"].get(TICK)
    if type(tick) not in (int, float) or not math.isfinite(tick) or tick < 0 or tick != int(tick):
        return []
    resources = observation["resourcesBefore"]
    cooldown = observation["cooldownRemaining"]
    latency = observation["state"].get("latency_p95_ms")

    def allowed(name, delta):
        if name not in resources:
            return False
        rule = control["rules"][name]
        target = resources[name] + delta
        if not rule["minValue"] <= target <= rule["maxValue"]:
            return False
        if delta == 0:
            return True
        if cooldown.get(name) != 0:
            return False
        down = rule.get("maxStepDown", 0) or rule["maxStep"]
        if delta > rule["maxStep"] or -delta > down:
            return False
        return (delta <= 0 or not rule.get("blockIncreaseOnSloViolation", True)
                or (type(latency) in (int, float) and math.isfinite(latency) and latency <= control["sloMs"]))

    return [[dl, dp] for dl in (-control["limitStep"], 0, control["limitStep"]) for dp in (-1, 0, 1)
            if (dp == 0 or int(tick) % control["poolPeriodTicks"] == 0)
            and allowed(LIMIT, dl) and allowed(POOL, dp)]


def state_is_measured(row, metadata):
    start = row["stateWindowStartedAt"]
    if start is None:
        return False
    at = timestamp(row["timestamp"])
    seconds = (at - timestamp(start)).total_seconds()
    nominal = row["windowSec"]
    timing = metadata["timing"]
    return (timestamp(start) >= timestamp(timing["measurementStartedAt"])
            and at <= timestamp(timing["measurementScheduledEndAt"])
            and abs(seconds - nominal) <= max(0.1, 0.25 * nominal))


def observation(row):
    seconds = (timestamp(row["timestamp"]) - timestamp(row["stateWindowStartedAt"])).total_seconds()
    return {"state": {name: row["state"].get(name) for name in (*METRICS, TICK, WINDOW)},
            "resourcesBefore": row["resourcesBefore"], "cooldownRemaining": row["cooldownRemaining"],
            "elapsedSeconds": seconds}


def samples(metadata, rows):
    """History never crosses episode, warmup, irregular intervals or decision gaps."""
    if metadata["mode"].removesuffix("-pinned") == "off":
        return [], {"off": len(rows)}
    control = context(metadata)
    output, excluded = [], {}

    def skip(reason):
        excluded[reason] = excluded.get(reason, 0) + 1

    for index, row in enumerate(rows):
        if row["outcome"] is None:
            skip("censored")
            continue
        if index < 3:
            skip("short_history")
            continue
        history_rows = rows[index - 3:index + 2]
        if len(history_rows) != 5 or not all(state_is_measured(r, metadata) for r in history_rows):
            skip("phase_or_interval")
            continue
        ticks = [r["state"].get(TICK) for r in history_rows]
        if any(type(t) not in (int, float) or not math.isfinite(t) or t != int(t) for t in ticks) \
                or any(b != a + 1 for a, b in zip(ticks, ticks[1:])):
            skip("missing_or_discontinuous_control_context")
            continue
        observations = [observation(r) for r in history_rows]
        legal = candidates(observations[-2], control)
        applied = [row["action"][name] - row["resourcesBefore"][name] for name in (LIMIT, POOL)]
        if applied not in legal:
            skip("action_outside_grid_or_mask")
            continue
        next_legal = candidates(observations[-1], control)
        if not next_legal:
            skip("no_next_candidate")
            continue
        output.append({"sampleVersion": 1, "sequence": row["sequence"], "history": observations[:4],
                       "nextHistory": observations[1:], "action": applied, "candidateActions": legal,
                       "nextCandidateActions": next_legal, "context": control,
                       "elapsedSeconds": (timestamp(row["outcomeTimestamp"]) - timestamp(row["timestamp"])).total_seconds()})
    return output, excluded


def state_bin(sample):
    """Diagnostic coarse bins. These are not a guarantee of coverage in feature space."""
    obs = sample["history"][-1]
    state = obs["state"]
    latency = state.get("latency_p95_ms")
    ratio = latency / sample["context"]["sloMs"] if latency is not None else None
    band = "missing" if ratio is None else "comfortable" if ratio < 0.7 else "within_slo" if ratio <= 1 else "over_slo"
    pending = state.get("hikari_pending")
    pool = "missing" if pending is None else "pending" if pending > 0 else "no_pending"
    inflight = state.get("inflight")
    limit = obs["resourcesBefore"][LIMIT]
    busy = "missing" if inflight is None else "binding" if inflight >= 0.9 * max(1, limit) else "headroom"
    tick = int(state[TICK])
    cadence = "pool_tick" if tick % sample["context"]["poolPeriodTicks"] == 0 else "limit_tick"
    return "/".join((band, pool, busy, cadence))
