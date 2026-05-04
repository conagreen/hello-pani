#!/usr/bin/env bash
#
# 피크 부하 시나리오 — k6/peak.js를 실행하고 build/load-report.md를 생성한다.
#
# 환경변수:
#   BASE_URL       대상 (기본: http://localhost:8080)
#                  localhost가 아니면 ensure_app/reset_state를 자동 스킵 (클라우드 대상 가정).
#   PEAK_RPS       목표 RPS (기본: 1000)
#   PEAK_DURATION  유지 시간 (기본: 60s, 풀 스펙은 5m)
#   PEAK_RAMP      ramp-up (기본: 15s)
#   SKIP_RESET     true면 reset_state 스킵 (로컬에서도 강제 스킵)

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command k6

# BASE_URL이 localhost가 아니면 클라우드/원격 대상으로 간주하고 로컬 인프라 작업을 건너뛴다.
TARGET_HOST="${BASE_URL#*://}"
TARGET_HOST="${TARGET_HOST%%:*}"
TARGET_HOST="${TARGET_HOST%%/*}"
IS_LOCAL=false
case "$TARGET_HOST" in
    localhost|127.0.0.1|host.docker.internal)
        IS_LOCAL=true
        ;;
esac

if $IS_LOCAL; then
    ensure_app
    reset_state
else
    echo "remote target detected ($BASE_URL). 로컬 앱 기동 / DB·Redis 초기화는 운영자 책임으로 둔다."
fi

mkdir -p build
echo "running peak load: target=${BASE_URL} rps=${PEAK_RPS:-1000} duration=${PEAK_DURATION:-60s}"
echo

k6 run \
    -e BASE_URL="$BASE_URL" \
    -e PEAK_RPS="${PEAK_RPS:-1000}" \
    -e PEAK_DURATION="${PEAK_DURATION:-60s}" \
    -e PEAK_RAMP="${PEAK_RAMP:-15s}" \
    k6/peak.js

echo
echo "=================================================="
echo "load report: build/load-report.md"
echo "=================================================="
