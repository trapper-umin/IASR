#!/usr/bin/env python3
"""Run a plan of benchmark cases against one disposable local PostgreSQL cluster.

The plan lists cases; each case is one BenchmarkRunner process with its own
conditions, modes and scenarios. The classpath is a frozen directory of jars so
that rebuilding the project while a long plan runs cannot change the code under
measurement.
"""
import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def now():
    return datetime.now(timezone.utc).isoformat()


def source_state():
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    diff = subprocess.check_output(["git", "diff", "HEAD"], cwd=REPO)
    tracked = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=REPO, text=True)
    untracked = subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO, text=True)
    # Untracked files cannot be on the classpath of a build from tracked sources; they are listed, not counted.
    return {"commit": commit, "dirty": bool(tracked.strip()),
            "trackedDiffSha256": hashlib.sha256(diff).hexdigest(), "status": untracked.splitlines()}


def freeze_classpath(destination):
    """Copy the benchmark's runtime classpath into one directory and return it."""
    destination = Path(destination)
    destination.mkdir(parents=True)
    import xml.etree.ElementTree as ET
    reports = sorted((REPO / "iasr-benchmark/target/surefire-reports").glob("TEST-*.xml"))
    assert reports, "run the Maven verification build first: its test report records the classpath"
    entries = next(p.attrib["value"] for p in ET.parse(reports[0]).getroot().findall("./properties/property")
                   if p.attrib["name"] == "java.class.path").split(os.pathsep)
    digests = {}
    for index, entry in enumerate(Path(e) for e in entries):
        if entry.name == "test-classes" or not entry.exists():
            continue
        target = destination / f"{index:03d}-{entry.name}"
        if entry.is_dir():
            shutil.copytree(entry, target)
            digest = hashlib.sha256()
            for file in sorted(target.rglob("*")):
                if file.is_file():
                    digest.update(str(file.relative_to(target)).encode() + file.read_bytes())
            digests[target.name] = digest.hexdigest()
        else:
            shutil.copy2(entry, target)
            digests[target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (destination / "jars.json").write_text(json.dumps(digests, indent=2) + "\n")
    return destination


def run(plan_path, output, classpath_dir):
    plan = json.loads(Path(plan_path).read_text())
    assert plan["planVersion"] == 1 and plan["cases"], "invalid plan"
    ids = [case["id"] for case in plan["cases"]]
    assert len(set(ids)) == len(ids), "duplicate case id"
    output = Path(output).resolve()
    output.mkdir(parents=True)
    classpath = os.pathsep.join(str(p) for p in sorted(Path(classpath_dir).iterdir()) if p.suffix == ".jar" or p.is_dir())
    assert classpath, "frozen classpath is empty"
    execution = {"planSha256": hashlib.sha256(Path(plan_path).read_bytes()).hexdigest(), "plan": plan,
                 "source": source_state(), "classpathDigest": json.loads((Path(classpath_dir) / "jars.json").read_text()),
                 "postgresVersion": subprocess.check_output(["postgres", "--version"], text=True).strip(),
                 "startedAt": now(), "status": "running", "completed": []}
    record = output / "execution.json"
    record.write_text(json.dumps(execution, indent=2) + "\n")

    scratch = Path(tempfile.mkdtemp(prefix="iasr-pg-", dir="/tmp"))
    data = scratch / "data"
    password = secrets.token_urlsafe(18)
    password_file = scratch / "bootstrap-password"
    password_file.write_text(password + "\n")
    password_file.chmod(0o600)
    started, finished = False, False
    try:
        with (output / "initdb.log").open("w") as log:
            subprocess.run(["initdb", "-D", str(data), "-U", "iasr", "--auth=scram-sha-256",
                            f"--pwfile={password_file}", "--locale=C", "-E", "UTF8"],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        password_file.unlink()
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        started = True
        # macOS refuses to start the postmaster when the inherited locale is not a valid one.
        server_env = {**os.environ, "LC_ALL": os.environ.get("IASR_PG_LOCALE", "en_US.UTF-8")}
        subprocess.run(["pg_ctl", "-D", str(data), "-l", str(output / "postgres.log"),
                        "-o", f"-h 127.0.0.1 -p {port} -k {scratch}", "-w", "start"], env=server_env, check=True)
        env = {**os.environ, "PGPASSWORD": password}
        subprocess.run(["createdb", "-h", "127.0.0.1", "-p", str(port), "-U", "iasr", "iasr"], env=env, check=True)
        caffeinate = shutil.which("caffeinate")
        prefix = [caffeinate, "-i"] if caffeinate else []
        for case in plan["cases"]:
            properties = {**plan.get("common", {}), **case.get("properties", {}),
                          "bench.db.real": True, "bench.db.url": f"jdbc:postgresql://127.0.0.1:{port}/iasr",
                          "bench.db.password": password, "bench.out": output / case["id"],
                          "bench.scenarios": ",".join(case["scenarios"]), "bench.modes": ",".join(case["modes"]),
                          "bench.repeat": case.get("repeats", 1), "bench.orderSeed": case.get("seed", plan.get("seed", 42)),
                          "bench.policySeed": case.get("seed", plan.get("seed", 42))}
            flags = [f"-D{k}={str(v).lower() if type(v) is bool else v}" for k, v in properties.items()]
            with (output / f"{case['id']}.log").open("w") as log:
                subprocess.run([*prefix, "java", *flags, "-cp", classpath, "com.iasr.benchmark.BenchmarkRunner"],
                               cwd=REPO, stdout=log, stderr=subprocess.STDOUT, check=True)
            execution["completed"].append({"id": case["id"], "endedAt": now()})
            record.write_text(json.dumps(execution, indent=2) + "\n")
        finished = True
    finally:
        stopped = not started or subprocess.run(
            ["pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop"], check=False).returncode == 0
        execution.update(status="complete" if finished and stopped else "failed", postgresStopped=stopped, endedAt=now())
        record.write_text(json.dumps(execution, indent=2) + "\n")
        if stopped:
            shutil.rmtree(scratch, ignore_errors=True)
    return 0 if finished else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    freeze = sub.add_parser("freeze", help="snapshot the benchmark classpath")
    freeze.add_argument("destination", type=Path)
    execute = sub.add_parser("run", help="execute a plan")
    execute.add_argument("plan", type=Path)
    execute.add_argument("--out", type=Path, required=True)
    execute.add_argument("--classpath", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "freeze":
        print(freeze_classpath(args.destination))
        return 0
    return run(args.plan, args.out, args.classpath)


if __name__ == "__main__":
    sys.exit(main())
