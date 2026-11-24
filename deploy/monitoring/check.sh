#!/usr/bin/env sh
set -eu

# Always validate the checked-in example, never the server .env.
monitoring_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose() {
  docker compose --project-name bunch-monitoring-check \
    --env-file "$monitoring_dir/.env.example" -f "$monitoring_dir/compose.yaml" "$@"
}

compose config --quiet
# These one-shot validators start no application, storage, or notification service.
# Bind mounts avoid creating deployment data volumes during CI validation.
docker run --rm -e MONITORING_ENVIRONMENT=test \
  -v "$monitoring_dir/config.alloy:/etc/alloy/config.alloy:ro" \
  "$(compose config --images alloy)" validate /etc/alloy/config.alloy
docker run --rm \
  -v "$monitoring_dir/loki.yaml:/etc/loki/config.yaml:ro" \
  "$(compose config --images loki)" -config.file=/etc/loki/config.yaml -verify-config=true
docker run --rm --entrypoint /bin/promtool \
  -v "$monitoring_dir/prometheus.yaml:/etc/prometheus/prometheus.yaml:ro" \
  "$(compose config --images prometheus)" check config /etc/prometheus/prometheus.yaml
printf '%s\n' 'Monitoring configuration checks passed.'
