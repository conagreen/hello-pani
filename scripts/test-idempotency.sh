#!/usr/bin/env bash

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command k6
ensure_app
reset_state

echo "running idempotency scenario: duplicate POST /bookings must create one booking/payment"
k6 run k6/idempotency.js
