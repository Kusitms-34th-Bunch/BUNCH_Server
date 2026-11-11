#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python3 - "$script_dir/server.mjs" <<'PY'
import os
import shlex
import signal
import subprocess
import sys

target = sys.argv[1]
stopped = 0
processes = subprocess.check_output(["ps", "-A", "-o", "pid=,command="], text=True)
for line in processes.splitlines():
    try:
        pid_text, command = line.strip().split(None, 1)
        args = shlex.split(command)
    except ValueError:
        continue
    if len(args) == 2 and os.path.basename(args[0]) == "node" and args[1] == target:
        try:
            os.kill(int(pid_text), signal.SIGTERM)
            stopped += 1
        except ProcessLookupError:
            pass
print(f"[bundle-lab] stopped {stopped} local lab server(s); launcher cleans up its backend")
PY
