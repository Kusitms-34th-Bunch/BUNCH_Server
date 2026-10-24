#!/usr/bin/env sh
set -eu

# Use placeholders only; never render the interpolated Compose configuration.
deploy_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compose() {
  docker compose --project-name bunch-monitoring-check \
    --env-file "$deploy_dir/.env.example" -f "$deploy_dir/compose.yaml" "$@"
}

compose config --no-env-resolution --quiet
# These one-shot validators start no application, storage, or notification service.
# Bind mounts avoid creating deployment data volumes during CI validation.
docker run --rm -e MONITORING_ENVIRONMENT=test \
  -v "$deploy_dir/monitoring/config.alloy:/etc/alloy/config.alloy:ro" \
  "$(compose config --no-env-resolution --images alloy)" validate /etc/alloy/config.alloy
docker run --rm \
  -v "$deploy_dir/monitoring/loki.yaml:/etc/loki/config.yaml:ro" \
  "$(compose config --no-env-resolution --images loki)" -config.file=/etc/loki/config.yaml -verify-config=true
docker run --rm --entrypoint /bin/promtool \
  -v "$deploy_dir/monitoring/prometheus.yaml:/etc/prometheus/prometheus.yaml:ro" \
  "$(compose config --no-env-resolution --images prometheus)" check config /etc/prometheus/prometheus.yaml
printf '%s\n' 'Monitoring configuration checks passed.'
