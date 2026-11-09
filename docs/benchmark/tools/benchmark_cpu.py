"""Sample cumulative CPU time of the benchmark and PostgreSQL process trees."""
import json
import math
import os
import signal
import subprocess
import time
from collections import defaultdict
from datetime import datetime, timezone

from validate_episodes import require, timestamp


def cpu_seconds(value):
    days, separator, clock = value.partition("-")
    fields = clock.split(":") if separator else days.split(":")
    return (int(days) * 86400 if separator else 0) + sum(
        float(field) * 60 ** i for i, field in enumerate(reversed(fields)))


def process_times(output, roots):
    parents, times = defaultdict(list), {}
    for line in output.splitlines():
        pid, parent, cpu = line.split()
        parents[int(parent)].append(int(pid))
        times[int(pid)] = cpu
    trees = {}
    for label, root in roots.items():
        queue = [root] if root in times else []
        for pid in queue:
            queue.extend(parents[pid])
        trees[label] = {pid: cpu_seconds(times[pid]) for pid in queue}
    return trees


def cpu_delta(previous, current):
    return sum(cpu - previous.get(pid, 0) for pid, cpu in current.items() if cpu >= previous.get(pid, 0))


def run_sampled(command, cwd, log, output, postgres_pid, interval):
    require(type(interval) in (float, int) and math.isfinite(interval) and 0.5 <= interval <= 5,
            "CPU sampling interval must be between 0.5 and 5 seconds")
    with output.open("x", encoding="utf-8") as trace, subprocess.Popen(
            command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True) as process:
        try:
            roots = {"jvm": process.pid, "postgres": postgres_pid}

            def snapshot():
                output = subprocess.check_output(["ps", "-A", "-o", "pid=,ppid=,time="], text=True)
                return datetime.now(timezone.utc).isoformat(), time.monotonic(), process_times(output, roots)

            start, tick, before = snapshot()
            require(before["postgres"], "temporary PostgreSQL process tree is missing")
            while process.poll() is None:
                try:
                    process.wait(timeout=max(0, interval - (time.monotonic() - tick)))
                except subprocess.TimeoutExpired:
                    end, next_tick, current = snapshot()
                    if process.poll() is not None:
                        break
                    require(current["jvm"] and current["postgres"], "sampled process tree is missing")
                    sample = {"startedAt": start, "endedAt": end, "elapsedSec": next_tick - tick}
                    for label in roots:
                        sample[label] = {"cpuSec": cpu_delta(before[label], current[label]),
                                         "started": len(current[label].keys() - before[label].keys()),
                                         "exited": len(before[label].keys() - current[label].keys())}
                    trace.write(json.dumps(sample, allow_nan=False) + "\n")
                    trace.flush()
                    start, tick, before = end, next_tick, current
            if process.returncode:
                raise subprocess.CalledProcessError(process.returncode, command)
        except BaseException:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            raise


def read_cpu_trace(path):
    samples = []
    with path.open(encoding="utf-8") as trace:
        for line in trace:
            row = json.loads(line)
            start, end = timestamp(row["startedAt"]), timestamp(row["endedAt"])
            require(start < end and (not samples or samples[-1][1] == start), "invalid CPU sample timing")
            require(math.isfinite(row["elapsedSec"]) and row["elapsedSec"] > 0, "invalid CPU sample duration")
            for label in ("jvm", "postgres"):
                sample = row[label]
                require(math.isfinite(sample["cpuSec"]) and sample["cpuSec"] >= 0, "invalid process CPU delta")
                require(all(type(sample[key]) is int and sample[key] >= 0 for key in ("started", "exited")),
                        "invalid process turnover")
            samples.append((start, end, row))
    require(samples, "missing CPU samples")
    return samples


def summarize_cpu(samples, start, end, interval):
    rows = [row for left, right, row in samples if start <= left and right <= end and row["elapsedSec"] <= 2.5 * interval]
    seconds = sum(row["elapsedSec"] for row in rows)
    require(seconds > 0, "no CPU samples within the measurement phase")
    return {"samples": len(rows), "sampledSeconds": seconds, "fractionCovered": seconds / (end - start).total_seconds(),
            **{label: {"meanCores": sum(row[label]["cpuSec"] for row in rows) / seconds,
                       "started": sum(row[label]["started"] for row in rows),
                       "exited": sum(row[label]["exited"] for row in rows)} for label in ("jvm", "postgres")}}
