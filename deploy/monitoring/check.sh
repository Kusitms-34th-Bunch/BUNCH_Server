#!/usr/bin/env sh
set -eu

monitoring_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Only the inert example is rendered; no local .env or shell secret overrides.
python3 "$monitoring_dir/config_test.py"
# These one-shot validators do not start the application or notifications.
docker run --rm \
  -v "$monitoring_dir/config.alloy:/etc/alloy/config.alloy:ro" \
  grafana/alloy:v1.20.1 validate /etc/alloy/config.alloy
docker run --rm \
  -v "$monitoring_dir/loki.yaml:/etc/loki/config.yaml:ro" \
  grafana/loki:3.7.8 -config.file=/etc/loki/config.yaml -verify-config=true
docker run --rm --entrypoint /bin/promtool \
  -v "$monitoring_dir/prometheus.yaml:/etc/prometheus/prometheus.yaml:ro" \
  prom/prometheus:v3.15.0 check config /etc/prometheus/prometheus.yaml
printf '%s\n' 'Monitoring configuration checks passed.'
