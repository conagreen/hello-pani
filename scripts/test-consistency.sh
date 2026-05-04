#!/usr/bin/env bash

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command k6
ensure_app
reset_state

echo "running consistency scenario: concurrent requests must produce exactly 10 CONFIRMED bookings"
k6 run k6/consistency.js
