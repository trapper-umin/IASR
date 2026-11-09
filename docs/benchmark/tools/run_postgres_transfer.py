#!/usr/bin/env python3
"""Run a frozen benchmark protocol in a disposable local PostgreSQL cluster."""
import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

from validate_episodes import read_json, require


def now():
    return datetime.now(timezone.utc).isoformat()


def run(plan_path, output):
    repo = Path(__file__).resolve().parents[3]
    plan_path, output = Path(plan_path).resolve(), Path(output).resolve()
    plan = read_json(plan_path)
    require(plan["protocolVersion"] == 1 and plan["properties"]["bench.db.real"] is True, "expected a PostgreSQL protocol")
    require(not output.is_relative_to(repo), "use an output directory outside the repository")
    require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=repo).strip(), "working tree must be clean")
    model = (repo / plan["model"]).resolve()
    require(hashlib.sha256(model.read_bytes()).hexdigest() == plan["modelSha256"], "model changed")
    calibration = plan_path.parent / plan["calibration"]["file"]
    require(hashlib.sha256(calibration.read_bytes()).hexdigest() == plan["calibration"]["sha256"], "calibration changed")
    version = subprocess.check_output(["postgres", "--version"], text=True).strip()
    require(version == plan["postgresVersion"], "PostgreSQL version differs from protocol")
    reports = sorted((repo / "iasr-benchmark/target/surefire-reports").glob("TEST-*.xml"))
    require(reports, "run the Maven verification build first")
    classpath = next((p.attrib["value"] for p in ET.parse(reports[0]).getroot().findall("./properties/property")
                      if p.attrib["name"] == "java.class.path"), None)
    require(classpath, "benchmark classpath is absent from the verification report")

    output.mkdir(parents=True)
    scratch = Path(tempfile.mkdtemp(prefix="iasr-pg-", dir="/tmp"))
    data = scratch / "data"
    password = secrets.token_urlsafe(18)
    password_file = scratch / "bootstrap-password"
    password_file.write_text(password + "\n")
    password_file.chmod(0o600)
    execution = {"protocolSha256": hashlib.sha256(plan_path.read_bytes()).hexdigest(), "postgresVersion": version,
                 "startedAt": now(), "status": "running"}
    (output / "execution.json").write_text(json.dumps(execution, indent=2) + "\n")
    startup_attempted, finished = False, False
    try:
        with (output / "initdb.log").open("w") as log:
            subprocess.run(["initdb", "-D", str(data), "-U", "iasr", "--auth=scram-sha-256",
                            f"--pwfile={password_file}", "--locale=C", "-E", "UTF8"],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        password_file.unlink()
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        startup_attempted = True
        subprocess.run(["pg_ctl", "-D", str(data), "-l", str(output / "postgres.log"),
                        "-o", f"-h 127.0.0.1 -p {port} -k {scratch}", "-w", "start"], check=True)
        db_connection = ["-h", "127.0.0.1", "-p", str(port), "-U", "iasr"]
        pg_env = {**os.environ, "PGPASSWORD": password}
        subprocess.run(["createdb", *db_connection, "iasr"], env=pg_env, check=True)
        settings = ("select name || '=' || setting from pg_settings where name in "
                    "('server_version','work_mem','jit','shared_buffers','max_connections','max_parallel_workers_per_gather') order by name")
        execution["postgresSettings"] = subprocess.check_output(
            ["psql", "-X", *db_connection, "-d", "iasr", "-At", "-c", settings], env=pg_env, text=True).splitlines()
        (output / "execution.json").write_text(json.dumps(execution, indent=2) + "\n")
        properties = {**plan["properties"], "bench.db.url": f"jdbc:postgresql://127.0.0.1:{port}/iasr",
                      "bench.db.password": password, "bench.out": output, "bench.model": model,
                      "bench.scenarios": ",".join(plan["scenarios"]), "bench.modes": ",".join(plan["modes"]),
                      "bench.repeat": plan["repeats"], "bench.orderSeed": plan["orderSeed"]}
        flags = [f"-D{key}={str(value).lower() if type(value) is bool else value}" for key, value in properties.items()]
        caffeinate = shutil.which("caffeinate")
        prefix = [caffeinate, "-i"] if caffeinate else []
        with (output / "run.log").open("w") as log:
            subprocess.run([*prefix, "java", *flags, "-cp", classpath, "com.iasr.benchmark.BenchmarkRunner"],
                           cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)
        finished = True
    finally:
        stopped = not startup_attempted or subprocess.run(
            ["pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop"], check=False).returncode == 0
        execution.update(status="complete" if finished and stopped else "failed", postgresStopped=stopped, endedAt=now())
        if not stopped:
            execution["postgresDataDirectory"] = str(data)
        (output / "execution.json").write_text(json.dumps(execution, indent=2) + "\n")
        if stopped:
            shutil.rmtree(scratch)
    require(stopped, "temporary PostgreSQL shutdown failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        run(args.protocol, args.out)
    except subprocess.CalledProcessError as error:
        parser.exit(1, f"Subprocess exited with status {error.returncode}. See logs in the output directory.\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"PostgreSQL transfer run failed: {error}\n")
