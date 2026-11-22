#!/usr/bin/env bash
# Candidate-only validation: release.sh <dev|prod|monitoring> <40-character SHA> --check
# --check may pull/cache the app image; it does not replace live files or start services.
set -euo pipefail
umask 077
fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ $# -ge 2 && $# -le 3 ]] || fail 'Usage: release.sh <dev|prod|monitoring> <SHA> [--check]'
environment=$1
sha=$2
mode=${3:-}
[[ "$environment" =~ ^(dev|prod|monitoring)$ && "$sha" =~ ^[0-9a-f]{40}$ ]] || fail 'Invalid environment or SHA.'
[[ -z "$mode" || "$mode" == --check ]] || fail 'Unknown option.'
envdir="$HOME/$environment"
stage=$(cd -- "$(dirname -- "$0")" && pwd -P)
[[ "$stage" == "$envdir/.incoming-$sha" ]] || fail 'Candidate must be inside the approved environment directory.'
[[ ! -L "$envdir" && -d "$envdir" && -f "$envdir/.env" ]] || fail 'Environment must be prepared manually.'
expected="bunch-environment-v1:$environment"
[[ "$environment" != monitoring ]] || expected=bunch-monitoring-v1
[[ -f "$envdir/.deployment-ready" && "$(cat "$envdir/.deployment-ready")" == "$expected" ]] || fail 'Deployment readiness marker is missing or incorrect.'
# A second lock also protects against simultaneous manual release invocations.
exec 9>"$envdir/.deployment.lock"
flock -n 9 || fail 'Another release is running.'
compose=(docker compose --project-directory "$envdir" --env-file "$envdir/.env")
[[ ! -f "$envdir/.release.env" || "$environment" == monitoring ]] || compose+=(--env-file "$envdir/.release.env")
compose+=(-f "$stage/compose.yaml")

if [[ "$environment" != monitoring ]]; then
  image="ghcr.io/kusitms-34th-bunch/bunch_server:$environment-$sha"
  docker pull "$image" >/dev/null 2>&1 || fail 'Cannot pull candidate application image.'
  digest=$(docker image inspect "$image" --format '{{range .RepoDigests}}{{println .}}{{end}}' | \
    sed -n '/^ghcr\.io\/kusitms-34th-bunch\/bunch_server@sha256:[0-9a-f]\{64\}$/p' | head -n 1)
  [[ "$digest" =~ ^ghcr.io/kusitms-34th-bunch/bunch_server@sha256:[0-9a-f]{64}$ ]] || fail 'Cannot resolve immutable application digest.'
  export APP_IMAGE="$digest"
  python3 "$stage/environment_check.py" "$environment" "$envdir" \
    --compose "$stage/compose.yaml" --image "$digest" --ready --resources
else
  # Never print interpolated config: it contains Grafana/Tunnel credentials.
  "${compose[@]}" config --quiet >/dev/null 2>&1 || fail 'Monitoring candidate configuration is invalid.'
  python3 - "$envdir" "$stage" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

envdir, stage = map(Path, sys.argv[1:])
sys.path.insert(0, str(stage))
command = ["docker", "compose", "--project-directory", str(envdir), "--env-file", str(envdir / ".env"),
           "-f", str(stage / "compose.yaml"), "config", "--format", "json"]
try:
    config = json.loads(subprocess.run(command, check=True, capture_output=True, text=True).stdout)
    from config_test import validate
    validate(config)
    if set(config["networks"]) != {"default", "dev_metrics", "prod_metrics"}:
        raise ValueError("Unexpected monitoring networks")
    for kind, inspect_kind in (("volumes", "volume"), ("networks", "network")):
        for resource in config.get(kind, {}).values():
            if resource.get("external"):
                subprocess.run(["docker", inspect_kind, "inspect", resource["name"]],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for name in ("compose.yaml", "config.alloy", "loki.yaml", "prometheus.yaml",
                 "grafana/provisioning", "grafana/dashboards"):
        if not (stage / name).exists():
            raise ValueError("Missing monitoring candidate file")
except (AssertionError, ImportError, OSError, ValueError, KeyError, subprocess.CalledProcessError):
    sys.exit("Monitoring configuration/resources validation failed; no live files changed.")
PY
fi
[[ "$mode" != --check ]] || { printf '%s\n' 'Candidate validated; live configuration unchanged.'; exit 0; }

backup="$envdir/.backups/$(date -u +%Y%m%dT%H%M%S)-$sha-$$"
mkdir -p "$backup"
files=(compose.yaml release.sh)
if [[ "$environment" == monitoring ]]; then
  files+=(config.alloy loki.yaml prometheus.yaml grafana)
else
  files+=(environment_check.py .release.env)
fi
for file in "${files[@]}"; do
  [[ ! -e "$envdir/$file" ]] || cp -a "$envdir/$file" "$backup/"
done
printf 'Rollback backup: %s\n' "$backup"
if [[ "$environment" != monitoring ]]; then
  # Candidate Compose uses the live project directory so relative mounts keep their identity.
  "${compose[@]}" up -d --no-deps --wait --wait-timeout 120 app
  container=$("${compose[@]}" ps -q app)
  [[ -n "$container" && "$container" != *$'\n'* ]] || fail 'Expected one application container.'
  # The Java runtime has no guaranteed curl/wget. The Linux Docker host can reach bridge IPs.
  # Only status is read; response bodies, environment values and application logs are never printed.
  python3 - "$container" <<'PY'
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

container = sys.argv[1]
end = time.monotonic() + 120
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
while time.monotonic() < end:
    try:
        result = subprocess.run(
            ["docker", "inspect", "--format",
             '{"running":{{json .State.Running}},"networks":{{json .NetworkSettings.Networks}}}', container],
            check=True, capture_output=True, text=True)
        state = json.loads(result.stdout)
        if not state["running"]:
            sys.exit("Application stopped before health verification; release record preserved.")
        for network in state["networks"].values():
            address = network.get("IPAddress")
            if address:
                with client.open(f"http://{address}:8081/actuator/health", timeout=3) as response:
                    if response.status == 200 and json.load(response).get("status") == "UP":
                        sys.exit(0)
    except (subprocess.CalledProcessError, OSError, ValueError, urllib.error.URLError):
        pass
    time.sleep(2)
sys.exit("Application health verification failed; release record preserved. Use the backup for manual rollback.")
PY
  install -m 600 "$stage/compose.yaml" "$envdir/compose.yaml"
  install -m 700 "$stage/release.sh" "$envdir/release.sh"
  install -m 700 "$stage/environment_check.py" "$envdir/environment_check.py"
  printf 'APP_IMAGE=%s\n' "$digest" > "$stage/.release.env"
  mv "$stage/.release.env" "$envdir/.release.env"
else
  # Pull the candidate images while the existing monitoring services still run.
  "${compose[@]}" pull alloy loki prometheus grafana
  live=(docker compose --project-directory "$envdir" --env-file "$envdir/.env" -f "$envdir/compose.yaml")
  # Stop exactly these four services before replacing any bind-mounted config.
  # The monitoring Tunnel, application services and all data volumes stay intact.
  "${live[@]}" stop alloy loki prometheus grafana
  for file in compose.yaml config.alloy loki.yaml prometheus.yaml; do
    install -m 644 "$stage/$file" "$envdir/$file"
  done
  # Remove stale provisioning files while preserving the previous tree in the backup.
  rm -rf -- "$envdir/grafana"
  cp -a "$stage/grafana" "$envdir/grafana"
  chmod -R u=rwX,go=rX "$envdir/grafana"
  install -m 700 "$stage/release.sh" "$envdir/release.sh"
  "${live[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 120 alloy loki prometheus grafana
fi
printf '%s\n' 'Release completed. Keep the backup until manual verification is complete.'
