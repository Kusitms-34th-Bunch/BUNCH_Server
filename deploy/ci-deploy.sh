#!/usr/bin/env bash
# CI transport only. Environment preparation and readiness approval are manual.
set -euo pipefail
umask 077
fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ $# == 2 ]] || fail 'Usage: ci-deploy.sh <dev|prod|monitoring> <SHA>'
environment=$1
sha=$2
[[ "$environment" =~ ^(dev|prod|monitoring)$ && "$sha" =~ ^[0-9a-f]{40}$ ]] || fail 'Invalid environment or SHA.'
: "${SSH_HOST:?}" "${SSH_USER:?}" "${SSH_PASSWORD:?}"
port=${SSH_PORT:-22}
[[ "$port" =~ ^[0-9]+$ && "$SSH_USER" =~ ^[a-z_][a-z0-9_-]*$ && "$SSH_HOST" =~ ^[a-zA-Z0-9.:-]+$ ]] || fail 'Invalid SSH target.'
if [[ "$environment" != monitoring ]]; then
  [[ "${EXPECTED_APP_IMAGE:-}" =~ ^ghcr\.io/kusitms-34th-bunch/bunch_server@sha256:[0-9a-f]{64}$ ]] || fail 'Invalid expected application digest.'
  : "${GITHUB_TOKEN:?}" "${GITHUB_ACTOR:?}"
  [[ "$GITHUB_ACTOR" =~ ^[a-zA-Z0-9_-]+(\[bot\])?$ ]] || fail 'Invalid registry actor.'
fi
target="$SSH_USER@$SSH_HOST"
options=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
remote() { sshpass -d 3 ssh "${options[@]}" -p "$port" "$target" "$@" 3<<< "$SSH_PASSWORD"; }
copy() { sshpass -d 3 scp "${options[@]}" -P "$port" "$@" 3<<< "$SSH_PASSWORD"; }
expected="bunch-environment-v1:$environment"
[[ "$environment" != monitoring ]] || expected=bunch-monitoring-v1
# This must be the first remote action: no mkdir, scp, login or file writes before approval.
ready="test ! -L \"\$HOME/$environment\" && test -f \"\$HOME/$environment/.deployment-ready\" && test \"\$(cat \"\$HOME/$environment/.deployment-ready\")\" = '$expected'"
remote "$ready" || fail 'Remote readiness check failed; nothing uploaded.'
remote "$ready && umask 077 && mkdir -p \"\$HOME/$environment/.incoming-$sha\""
stage="$environment/.incoming-$sha"
if [[ "$environment" == monitoring ]]; then
  # Explicit allowlist: local .env and .env.example are never uploaded.
  copy deploy/monitoring/compose.yaml deploy/monitoring/config.alloy deploy/monitoring/loki.yaml \
    deploy/monitoring/prometheus.yaml deploy/monitoring/config_test.py deploy/release.sh "$target:$stage/"
  copy -r deploy/monitoring/grafana "$target:$stage/"
  remote "$ready && bash \"\$HOME/$stage/release.sh\" monitoring '$sha'"
else
  copy deploy/compose.yaml deploy/release.sh deploy/environment_check.py "$target:$stage/"
  # A temporary Docker credential directory avoids altering the account's existing registry login.
  # The token is sent over stdin, never in the SSH command or on the server command line.
  printf '%s' "$GITHUB_TOKEN" | remote "$ready || exit 1; set -eu; \
    export DOCKER_CONFIG=\"\$HOME/$stage/.docker-auth\"; \
    mkdir -m 700 -p \"\$DOCKER_CONFIG\"; \
    trap 'rm -rf -- \"\$DOCKER_CONFIG\"' EXIT; \
    docker login ghcr.io -u '$GITHUB_ACTOR' --password-stdin >/dev/null 2>&1; \
    EXPECTED_APP_IMAGE='$EXPECTED_APP_IMAGE' bash \"\$HOME/$stage/release.sh\" '$environment' '$sha'"
fi
