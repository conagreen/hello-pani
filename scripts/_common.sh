#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
APP_PID=""

cd "$ROOT_DIR"

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "missing command: $command_name" >&2
        exit 1
    fi
}

is_app_up() {
    require_command curl
    curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1
}

cleanup_app() {
    if [[ -n "$APP_PID" ]]; then
        echo "stopping app started by script (pid=$APP_PID)"
        kill "$APP_PID" >/dev/null 2>&1 || true
        wait "$APP_PID" >/dev/null 2>&1 || true
    fi
}

ensure_app() {
    require_command curl
    if is_app_up; then
        echo "app is already running at $BASE_URL"
        return
    fi

    mkdir -p build
    echo "app is not running. starting ./gradlew bootRun in background..."
    ./gradlew bootRun > build/script-bootRun.log 2>&1 &
    APP_PID="$!"
    trap cleanup_app EXIT

    for _ in {1..90}; do
        if is_app_up; then
            echo "app is ready at $BASE_URL"
            return
        fi
        sleep 1
    done

    echo "app did not become healthy within 90 seconds" >&2
    echo "see build/script-bootRun.log" >&2
    exit 1
}

require_docker_compose() {
    if ! docker compose ps >/dev/null 2>&1; then
        echo "docker compose is not available or Docker is not running" >&2
        exit 1
    fi
}

reset_state() {
    if [[ "${SKIP_RESET:-false}" == "true" ]]; then
        echo "skip reset because SKIP_RESET=true"
        return
    fi
    require_docker_compose
    ./k6/reset.sh
}
