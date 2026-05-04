#!/usr/bin/env bash
#
# 피크 부하 시나리오 — k6/peak.js를 실행하고 build/load-report-${SCENARIO}.md 생성.
#
# 시나리오 (SCENARIO 환경변수):
#   rush   기본. 오픈 직후 즉시 구매 패턴. 1000 RPS / 60s
#   browse 오픈 대기 새로고침 패턴. GET /checkout만. 300 RPS / 60s
#   spike  대기 → 풀림 → 폭주 2-phase. browse(200/30s) → rush(1000/30s)
#
# 환경변수:
#   BASE_URL          대상 (기본: http://localhost:8080).
#                     localhost가 아니면 ensure_app/reset_state를 자동 스킵.
#   SCENARIO          rush | browse | spike (기본: rush)
#   PEAK_RPS, PEAK_DURATION, PEAK_RAMP
#   BROWSE_RPS, BROWSE_DURATION  (spike 전용)
#   SKIP_RESET=true   reset_state 강제 스킵

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command k6

SCENARIO="${SCENARIO:-rush}"
case "$SCENARIO" in
    rush|browse|spike) ;;
    *)
        echo "unknown SCENARIO: $SCENARIO (rush | browse | spike 중 하나)" >&2
        exit 1
        ;;
esac

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

echo "running scenario=${SCENARIO} target=${BASE_URL}"
case "$SCENARIO" in
    rush)
        echo "  rush: ${PEAK_RPS:-1000} RPS / ${PEAK_DURATION:-60s}"
        ;;
    browse)
        echo "  browse: ${PEAK_RPS:-300} RPS / ${PEAK_DURATION:-60s} (GET /checkout만)"
        ;;
    spike)
        echo "  browse → rush: ${BROWSE_RPS:-200} RPS / ${BROWSE_DURATION:-30s} → ${PEAK_RPS:-1000} RPS / ${PEAK_DURATION:-30s}"
        ;;
esac
echo

k6_args=(
    -e BASE_URL="$BASE_URL"
    -e SCENARIO="$SCENARIO"
)
[[ -n "${PEAK_RPS:-}" ]] && k6_args+=(-e PEAK_RPS="$PEAK_RPS")
[[ -n "${PEAK_DURATION:-}" ]] && k6_args+=(-e PEAK_DURATION="$PEAK_DURATION")
[[ -n "${PEAK_RAMP:-}" ]] && k6_args+=(-e PEAK_RAMP="$PEAK_RAMP")
[[ -n "${BROWSE_RPS:-}" ]] && k6_args+=(-e BROWSE_RPS="$BROWSE_RPS")
[[ -n "${BROWSE_DURATION:-}" ]] && k6_args+=(-e BROWSE_DURATION="$BROWSE_DURATION")

k6 run "${k6_args[@]}" k6/peak.js

REPORT="build/load-report-${SCENARIO}.md"
echo
echo "=================================================="
echo "load report: $REPORT"
echo "=================================================="
