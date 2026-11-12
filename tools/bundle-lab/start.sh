#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
JAR="$REPO_ROOT/build/libs/app.jar"
BACKEND_PORT=18080
MANAGEMENT_PORT=18081
POSTGRES_CONTAINER=bunch-lab107-postgres
REDIS_CONTAINER=bunch-lab107-redis
LAB_DATABASE=bunch_bundle_lab107_v42

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "[bundle-lab] required command is missing: $1" >&2
        exit 1
    }
}

port_is_free() {
    python3 - "$1" <<'PY'
import socket
import sys

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind(("127.0.0.1", int(sys.argv[1])))
except OSError:
    raise SystemExit(1)
finally:
    sock.close()
PY
}

container_is_running() {
    [[ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)" == "true" ]]
}

for command in docker python3 openssl curl node npm; do
    require_command "$command"
done

if [[ "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
    java21_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$java21_home" ]]; then
        export JAVA_HOME="$java21_home"
    fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "[bundle-lab] JDK 21 is required" >&2
    exit 1
fi

java_version="$($JAVA_HOME/bin/java -version 2>&1 | sed -n '1p')"
if [[ ! "$java_version" =~ version\ \"21([.\"]|$) ]]; then
    echo "[bundle-lab] JDK 21 is required" >&2
    exit 1
fi

for name in "$POSTGRES_CONTAINER" "$REDIS_CONTAINER"; do
    if ! container_is_running "$name"; then
        if docker inspect "$name" >/dev/null 2>&1; then
            docker start "$name" >/dev/null
        elif [[ "$name" == "$POSTGRES_CONTAINER" ]]; then
            port_is_free 25432 || { echo "[bundle-lab] database port25432 is in use" >&2; exit 1; }
            docker run -d --name "$name" -e POSTGRES_USER=bunch -e POSTGRES_PASSWORD=bunch_local \
                -e POSTGRES_DB=postgres -p 127.0.0.1:25432:5432 postgres:16 >/dev/null
        else
            port_is_free 26379 || { echo "[bundle-lab] redis port26379 is in use" >&2; exit 1; }
            docker run -d --name "$name" -p 127.0.0.1:26379:6379 redis:7 >/dev/null
        fi
    fi
done
for attempt in $(seq 1 30); do
    docker exec "$POSTGRES_CONTAINER" pg_isready -U bunch >/dev/null 2>&1 && break
    sleep 1
done

if ! docker port "$POSTGRES_CONTAINER" 5432/tcp 2>/dev/null | grep -Eq '127\.0\.0\.1:25432$'; then
    echo "[bundle-lab] bunch-lab107-postgres must expose 127.0.0.1:25432" >&2
    exit 1
fi
if ! docker port "$REDIS_CONTAINER" 6379/tcp 2>/dev/null | grep -Eq '127\.0\.0\.1:26379$'; then
    echo "[bundle-lab] bunch-lab107-redis must expose 127.0.0.1:26379" >&2
    exit 1
fi

if ! port_is_free "$BACKEND_PORT" || ! port_is_free "$MANAGEMENT_PORT"; then
    echo "[bundle-lab] backend ports 18080/18081 are already in use; no process was stopped" >&2
    exit 1
fi

if ! docker exec "$POSTGRES_CONTAINER" psql -X -q -tAc 'SELECT 1' -U bunch -d postgres >/dev/null 2>&1; then
    echo "[bundle-lab] cannot connect to bunch-lab107-postgres" >&2
    exit 1
fi

database_exists="$(docker exec "$POSTGRES_CONTAINER" psql -X -q -tAc \
    "SELECT 1 FROM pg_database WHERE datname = '${LAB_DATABASE}'" -U bunch -d postgres | tr -d '[:space:]')"
if [[ "$database_exists" != "1" ]]; then
    docker exec "$POSTGRES_CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -U bunch -d postgres \
        -c "CREATE DATABASE ${LAB_DATABASE} OWNER bunch" >/dev/null
fi

lab_port="${BUNDLE_LAB_PORT:-}"
if [[ -n "$lab_port" ]]; then
    if ! [[ "$lab_port" =~ ^[0-9]+$ ]] || (( lab_port < 1024 || lab_port > 65535 )) || ! port_is_free "$lab_port"; then
        echo "[bundle-lab] requested BUNDLE_LAB_PORT is unavailable; no process was stopped" >&2
        exit 1
    fi
else
    for candidate in 3300 3000 3301 3302 3303 3304 3305 3306 3307 3308 3309 3310; do
        if port_is_free "$candidate"; then
            lab_port="$candidate"
            break
        fi
    done
fi
if [[ -z "$lab_port" ]]; then
    echo "[bundle-lab] no local lab port is available" >&2
    exit 1
fi

if [[ ! -f "$SCRIPT_DIR/node_modules/@automerge/automerge/dist/mjs/entrypoints/fullfat_base64.js" ]]; then
    npm --prefix "$SCRIPT_DIR" ci --ignore-scripts --no-audit --no-fund
fi
lab_origin="http://localhost:${lab_port}"
jwt_secret="$(openssl rand -base64 96 | tr -d '\n')"
if [[ -z "$jwt_secret" ]]; then
    echo "[bundle-lab] could not create a local JWT secret" >&2
    exit 1
fi

needs_build=0
if [[ ! -f "$JAR" ]]; then
    needs_build=1
elif [[ "$REPO_ROOT/build.gradle.kts" -nt "$JAR" || "$REPO_ROOT/settings.gradle.kts" -nt "$JAR" ]]; then
    needs_build=1
else
    newer_source="$(find "$REPO_ROOT/src/main" -type f -newer "$JAR" -print -quit)"
    [[ -n "$newer_source" ]] && needs_build=1
fi
if (( needs_build )); then
    echo "[bundle-lab] building backend bootJar with JDK 21"
    (cd "$REPO_ROOT" && ./gradlew --console=plain bootJar -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process --max-workers=2)
fi

backend_pid=""
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" 2>/dev/null; then
        kill "$backend_pid" 2>/dev/null || true
        wait "$backend_pid" 2>/dev/null || true
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

(
    cd "$REPO_ROOT"
    exec env \
        SERVER_ADDRESS=127.0.0.1 \
        SERVER_PORT="$BACKEND_PORT" \
        MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
        MANAGEMENT_SERVER_PORT="$MANAGEMENT_PORT" \
        LOCAL_MANAGEMENT_PORT="$MANAGEMENT_PORT" \
        DB_URL="jdbc:postgresql://127.0.0.1:25432/${LAB_DATABASE}" \
        DB_USERNAME=bunch \
        DB_PASSWORD=bunch_local \
        REDIS_HOST=127.0.0.1 \
        REDIS_PORT=26379 \
        SPRING_DATA_REDIS_DATABASE=13 \
        SPRING_PROFILES_ACTIVE=local \
        JWT_SECRET="$jwt_secret" \
        APPLE_CLIENT_ID=bundle-lab-apple \
        GOOGLE_CLIENT_ID=bundle-lab-google \
        KAKAO_NATIVE_APP_KEY=bundle-lab-kakao \
        BUNCH_WS_ALLOWED_ORIGINS="$lab_origin" \
        BUNCH_INVITATION_BASE_URL="${lab_origin}/invite" \
        ASYNC_JOB_WORKER_ENABLED=false \
        "$JAVA_HOME/bin/java" -jar "$JAR" \
        --spring.profiles.active=local \
        --server.address=127.0.0.1 \
        --server.port="$BACKEND_PORT" \
        --management.server.address=127.0.0.1 \
        --management.server.port="$MANAGEMENT_PORT"
) &
backend_pid=$!

backend_ready=0
for _attempt in $(seq 1 90); do
    if ! kill -0 "$backend_pid" 2>/dev/null; then
        break
    fi
    if curl -fsS --max-time 3 "http://127.0.0.1:${BACKEND_PORT}/v3/api-docs" >/dev/null 2>&1; then
        backend_ready=1
        break
    fi
    sleep 1
done
if (( ! backend_ready )); then
    echo "[bundle-lab] backend did not become ready on 127.0.0.1:${BACKEND_PORT}" >&2
    exit 1
fi

echo "[bundle-lab] backend ready on 127.0.0.1:${BACKEND_PORT}; lab uses ${lab_origin}"

set +e
JWT_SECRET="$jwt_secret" \
    BUNDLE_LAB_SEED_PIPE=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    python3 "$SCRIPT_DIR/seed.py" |
    BUNDLE_LAB_SEED_PIPE=1 \
    BUNDLE_LAB_PORT="$lab_port" \
    BUNDLE_LAB_ORIGIN="$lab_origin" \
    BUNDLE_LAB_BACKEND_PORT="$BACKEND_PORT" \
    BUNDLE_LAB_STATIC_ROOT="$SCRIPT_DIR" \
    BUNDLE_LAB_AUTOMERGE_ROOT="$SCRIPT_DIR/node_modules/@automerge/automerge/dist/mjs" \
    env -u JWT_SECRET node "$SCRIPT_DIR/server.mjs"
pipeline_status=(${PIPESTATUS[@]})
set -e

if (( pipeline_status[0] != 0 )); then
    echo "[bundle-lab] fixture seed failed" >&2
    exit 1
fi
exit "${pipeline_status[1]}"
