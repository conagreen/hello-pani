#!/usr/bin/env bash

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command k6

echo "running unit/integration tests"
./gradlew test --rerun-tasks

ensure_app

echo "running consistency scenario"
reset_state
k6 run k6/consistency.js

echo "running idempotency scenario"
reset_state
k6 run k6/idempotency.js
