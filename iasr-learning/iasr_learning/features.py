"""Feature contract of the coordinated policy, version 3.

The contract is mirrored operation for operation by
``com.iasr.policy.coordinated.PolicyFeatures``. Keep both sides in step: every
arithmetic expression here has a twin there, evaluated in the same order in
IEEE double precision, and the result is narrowed to float32 only at the very
end, as the trees expect.

A state is described by smoothed, scale-free signals rather than by raw windows:
latency relative to the objective, the share of offered load served within it,
and, for every soft resource, how much of its capacity is used, how much work
waits for it and how long, and how the demand compares to the capacity. An
action is described by what it leaves behind — the capacities after the move
and how they relate to demand and to each other — so that the value of a joint
move can depend on the combination and not only on each resource separately.
"""
import math

FEATURE_VERSION = 3
ALPHA = 0.4            # weight of the newest window in the exponential average
RATIO_CAP = 50.0       # ratios are clipped to keep a stalled window from producing infinities
MIN_SERVED_SHARE = 0.05

GLOBAL_FEATURES = ["p95_slo", "p95_slo_avg", "mean_slo_avg", "rejected", "rejected_avg", "errors_avg",
                   "cpu", "within_slo", "within_slo_avg", "served_within_slo", "served_within_slo_avg"]
RESOURCE_FEATURES = ["capacity", "usage_avg", "backlog_avg", "wait_slo_avg", "hold_slo_avg", "hold_slo",
                     "demand", "usage"]
ACTION_FEATURES = ["change", "capacity_after", "demand_after", "load_after"]

# Signals of the default resources, identical to SoftResource.CONCURRENCY / CONNECTION_POOL.
CONCURRENCY = {"actuator": "concurrency_limit", "usage": "inflight", "backlog": "queue_length",
               "wait": "admission_wait_mean_ms", "hold": "service_mean_ms"}
CONNECTION_POOL = {"actuator": "hikari_max_pool", "usage": "hikari_active", "backlog": "hikari_pending",
                   "wait": "hikari_acquire_mean_ms", "hold": "dependency_mean_ms"}


def state_feature_names(resources):
    names = list(GLOBAL_FEATURES)
    for resource in resources:
        names += [f"{resource['actuator']}.{name}" for name in RESOURCE_FEATURES]
    names += [f"{b['actuator']}/{a['actuator']}" for a, b in zip(resources, resources[1:])]
    return names


def action_feature_names(resources):
    names = []
    for resource in resources:
        names += [f"{resource['actuator']}.{name}" for name in ACTION_FEATURES]
    names += [f"{b['actuator']}/{a['actuator']}.after" for a, b in zip(resources, resources[1:])]
    return names


def number(value, fallback=0.0):
    """A finite float, or the fallback for missing, null and non-finite values."""
    if value is None or isinstance(value, bool):
        return fallback
    value = float(value)
    return value if math.isfinite(value) else fallback


def clip(value):
    return 0.0 if value < 0.0 else RATIO_CAP if value > RATIO_CAP else value


def is_active(state):
    """A window carries information when it served or rejected something."""
    return number(state.get("goodput_rps")) >= 1.0 or number(state.get("timeout_rate")) > 0.0


def served_within_slo(state):
    """Share of the offered requests that were answered successfully within the objective."""
    share = 1.0 - number(state.get("timeout_rate")) - number(state.get("error_rate"))
    if share < 0.0:
        share = 0.0
    return share * number(state.get("slo_good_fraction"), 1.0)


class Smoother:
    """Exponential averages of the signals of consecutive active windows."""

    def __init__(self, resources, slo_ms):
        self.resources = resources
        self.slo_ms = float(slo_ms)
        self.reset()

    def reset(self):
        self.count = 0
        self.average = {}

    def _update(self, key, value):
        previous = self.average.get(key)
        self.average[key] = value if previous is None else previous + ALPHA * (value - previous)
        return self.average[key]

    def observe(self, state, capacities):
        """Fold one active window in and return its state features."""
        slo = self.slo_ms
        p95 = number(state.get("latency_p95_ms"))
        rejected = number(state.get("timeout_rate"))
        errors = number(state.get("error_rate"))
        within = number(state.get("slo_good_fraction"), 1.0)
        served = served_within_slo(state)
        share = 1.0 - rejected - errors
        if share < MIN_SERVED_SHARE:
            share = MIN_SERVED_SHARE
        offered = self._update("offered", number(state.get("goodput_rps")) / share)
        features = [clip(p95 / slo), clip(self._update("p95", p95) / slo),
                    clip(self._update("mean", number(state.get("latency_mean_ms"))) / slo),
                    rejected, self._update("rejected", rejected), self._update("errors", errors),
                    number(state.get("cpu_proc")), within, self._update("within", within),
                    served, self._update("served", served)]
        self.demand, self.load = [], []
        for resource, capacity in zip(self.resources, capacities):
            name = resource["actuator"]
            capacity = float(capacity if capacity > 1 else 1)
            usage = number(state.get(resource["usage"]))
            hold = number(state.get(resource["hold"]))
            usage_avg = self._update(name + ".usage", usage)
            backlog_avg = self._update(name + ".backlog", number(state.get(resource["backlog"])))
            wait_avg = self._update(name + ".wait", number(state.get(resource["wait"])))
            hold_avg = self._update(name + ".hold", hold)
            demand = offered * hold_avg / 1000.0
            self.demand.append(demand)
            self.load.append(usage_avg + backlog_avg)
            features += [capacity, clip(usage_avg / capacity), clip(backlog_avg / capacity), clip(wait_avg / slo),
                         clip(hold_avg / slo), clip(hold / slo), clip(demand / capacity), clip(usage / capacity)]
        for first, second in zip(capacities, capacities[1:]):
            features.append(clip(float(second) / float(first if first > 1 else 1)))
        self.count += 1
        return features

    def action_features(self, capacities, targets):
        """Describe a joint move by the configuration it leaves behind; valid after ``observe``."""
        features = []
        for index, (capacity, target) in enumerate(zip(capacities, targets)):
            capacity = float(capacity if capacity > 1 else 1)
            after = float(target if target > 1 else 1)
            features += [(float(target) - capacity) / capacity, float(target),
                         clip(self.demand[index] / after), clip(self.load[index] / after)]
        for first, second in zip(targets, targets[1:]):
            features.append(clip(float(second) / float(first if first > 1 else 1)))
        return features


def level_delta(level, current):
    """Signed change of one action level; twin of ActionLevel.delta."""
    relative, min_step = level
    if relative == 0:
        return 0
    magnitude = int(max(min_step, math.floor(abs(relative) * current + 0.5)))
    return magnitude if relative > 0 else -magnitude


def targets(rule, levels, current, cooldown_remaining, latency_p95_ms, slo_ms):
    """Distinct capacities one resource may take now; twin of CoordinatedActionSpace.targets."""
    result = [current]
    if cooldown_remaining is None or not math.isfinite(cooldown_remaining) or cooldown_remaining > 0:
        return result
    blocked = rule["blockIncreaseOnSloViolation"] and not (
        latency_p95_ms is not None and math.isfinite(latency_p95_ms) and latency_p95_ms <= slo_ms)
    max_down = rule["maxStepDown"] if rule.get("maxStepDown", 0) > 0 else rule["maxStep"]
    for level in levels:
        delta = level_delta(level, current)
        if delta == 0 or (delta > 0 and blocked):
            continue
        delta = max(-max_down, min(rule["maxStep"], delta))
        target = max(rule["minValue"], min(rule["maxValue"], current + delta))
        if (target > current if delta > 0 else target < current) and target not in result:
            result.append(target)
    return result


def clamp(rule, current, desired, cooldown_remaining, latency_p95_ms, slo_ms):
    """Where the guardrails would put a requested value; twin of CoordinatedActionSpace.clamp."""
    delta = desired - current
    if delta == 0:
        return current
    if cooldown_remaining is None or not (cooldown_remaining <= 0):
        return current
    if (delta > 0 and rule["blockIncreaseOnSloViolation"] and latency_p95_ms is not None
            and math.isfinite(latency_p95_ms) and latency_p95_ms > slo_ms):
        return current
    max_down = rule["maxStepDown"] if rule.get("maxStepDown", 0) > 0 else rule["maxStep"]
    delta = max(-max_down, min(rule["maxStep"], delta))
    return max(rule["minValue"], min(rule["maxValue"], current + delta))


def candidates(rules, levels, capacities, cooldowns, latency_p95_ms, slo_ms):
    """Every joint action available now, the first being "hold everything"."""
    joint = [[]]
    for rule, resource_levels, current, cooldown in zip(rules, levels, capacities, cooldowns):
        options = targets(rule, resource_levels, current, cooldown, latency_p95_ms, slo_ms)
        joint = [prefix + [option] for prefix in joint for option in options]
    return joint


def support_bin(state, resources, slo_ms):
    """Coarse kind of state used to check that an action was tried in similar conditions."""
    p95 = number(state.get("latency_p95_ms"))
    parts = ["comfortable" if p95 < 0.7 * slo_ms else "within_slo" if p95 <= slo_ms else "over_slo"]
    parts += ["backlog" if number(state.get(resource["backlog"])) > 0 else "free" for resource in resources]
    return "/".join(parts)


def direction(capacities, chosen):
    """Signs of a joint move, the key under which support is counted."""
    return ",".join(str((t > c) - (t < c)) for c, t in zip(capacities, chosen))
