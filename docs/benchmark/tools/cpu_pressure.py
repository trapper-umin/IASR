"""Own a short-lived process group of CPU workers for a benchmark trial."""
import hashlib
import os
import select
import signal
import subprocess
import sys
from contextlib import contextmanager
from pathlib import Path

from validate_episodes import require


@contextmanager
def cpu_pressure(workers):
    require(type(workers) is int and 0 <= workers <= 8, "CPU pressure needs between 0 and 8 workers")
    if workers == 0:
        yield None
        return

    command = [sys.executable, str(Path(__file__).resolve()), str(workers)]
    with subprocess.Popen(command, stdout=subprocess.PIPE, start_new_session=True) as process:
        try:
            readable, _, _ = select.select([process.stdout], [], [], 5)
            require(readable and process.stdout.readline() == b"ready\n", "CPU workers failed to start")
            yield process.pid
            require(process.poll() is None, "a CPU worker exited during the trial")
        finally:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()


def burn(workers):
    children = []

    def stop(*_):
        raise SystemExit(0)

    try:
        for _ in range(workers):
            pid = os.fork()
            if pid == 0:
                try:
                    while True:
                        hashlib.pbkdf2_hmac("sha256", b"iasr-cpu", b"local-benchmark", 20000)
                finally:
                    os._exit(1)
            children.append(pid)
        signal.signal(signal.SIGTERM, stop)
        print("ready", flush=True)
        exited, _ = os.wait()
        children.remove(exited)
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        for pid in children:
            try:
                if os.waitpid(pid, os.WNOHANG)[0] == 0:
                    try:
                        os.kill(pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    os.waitpid(pid, 0)
            except ChildProcessError:
                pass


if __name__ == "__main__":
    burn(int(sys.argv[1]))
