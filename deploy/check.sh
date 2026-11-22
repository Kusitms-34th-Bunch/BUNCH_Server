#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# These examples contain no real credentials. Do not read a developer/server .env.
docker compose --env-file deploy/.env.example -f deploy/compose.yaml config --quiet
docker compose --env-file deploy/environments/.env.example -f deploy/compose.yaml config --quiet
for script in deploy/*.sh; do
  bash -n "$script"
done
python3 -B -m unittest discover -s deploy/tests -v
printf '%s\n' 'Deployment configuration checks passed.'
