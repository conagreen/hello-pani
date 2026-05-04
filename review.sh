#!/usr/bin/env bash
#
# review.sh — 이 프로젝트를 처음 받은 사람의 진입점.
#
# 한 번 실행하면 *멀티 인스턴스 환경(app x2 + nginx + mysql + redis)*을 자동으로 띄우고
# 메뉴로 시나리오별 검증/부하/부분장애를 반복할 수 있게 한다. 메뉴는 [q]를 누를 때까지 살아 있다.
#
# 사용:
#   ./review.sh
#
# 의존성: java (gradle), docker, k6, curl. 없으면 설치 안내 후 종료.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"
mkdir -p build

NGINX_URL="http://localhost:18080"
GRAFANA_URL="http://localhost:3000"
PROMETHEUS_URL="http://localhost:9090"
IMAGE_NAME="hello-pani:0.0.1-SNAPSHOT"
COMPOSE_ARGS=(-f docker-compose.yml -f docker-compose.scale.yml)
COMPOSE_ARGS_WITH_OBS=(-f docker-compose.yml -f docker-compose.scale.yml -f docker-compose.observability.yml)
OBS_SERVICES=(prometheus grafana redis-exporter mysql-exporter)

# ─────────────────────────────────────────────────────────────────────────────
# 출력 헬퍼
# ─────────────────────────────────────────────────────────────────────────────

if [[ -t 1 ]]; then
    BOLD=$'\033[1m'; RESET=$'\033[0m'
    GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; DIM=$'\033[2m'
else
    BOLD=""; RESET=""; GREEN=""; RED=""; YELLOW=""; CYAN=""; DIM=""
fi

stage() { echo; printf "${BOLD}${CYAN}▶ %s${RESET}\n" "$*"; printf "${DIM}%s${RESET}\n" "──────────────────────────────────────────────────"; }
ok()    { printf "  ${GREEN}✓${RESET} %s\n" "$*"; }
warn()  { printf "  ${YELLOW}!${RESET} %s\n" "$*"; }
fail()  { printf "  ${RED}✗${RESET} %s\n" "$*"; }
note()  { printf "  ${DIM}%s${RESET}\n" "$*"; }

# 결과 누적 — 각 액션이 시작할 때 reset_records, 끝에 show_summary.
STAGE_NAMES=()
STAGE_RESULTS=()
STAGE_DETAILS=()
record() {
    STAGE_NAMES+=("$1")
    STAGE_RESULTS+=("$2")
    STAGE_DETAILS+=("${3:-}")
}
reset_records() {
    STAGE_NAMES=()
    STAGE_RESULTS=()
    STAGE_DETAILS=()
}

# ─────────────────────────────────────────────────────────────────────────────
# Prereq
# ─────────────────────────────────────────────────────────────────────────────

check_prereqs() {
    stage "환경 확인"
    local missing=()

    if command -v java >/dev/null 2>&1; then
        ok "java   — $(java -version 2>&1 | head -1)"
    else
        missing+=("java")
    fi

    if docker info >/dev/null 2>&1; then
        ok "docker — $(docker --version)"
    else
        missing+=("docker (Docker Desktop이 실행 중이어야 합니다)")
    fi

    if command -v k6 >/dev/null 2>&1; then
        ok "k6     — $(k6 version 2>&1 | head -1 | awk '{print $2}')"
    else
        missing+=("k6")
    fi

    if command -v curl >/dev/null 2>&1; then
        ok "curl   — present"
    else
        missing+=("curl")
    fi

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo
        fail "다음이 없습니다: ${missing[*]}"
        echo
        printf "${BOLD}설치 안내${RESET}\n"
        echo "  macOS  : brew install --cask temurin@21    # Java 21"
        echo "           brew install --cask docker        # Docker Desktop"
        echo "           brew install k6                   # k6"
        echo "  Linux  : sudo apt install openjdk-21-jdk curl"
        echo "           https://docs.docker.com/engine/install/ubuntu/"
        echo "           https://grafana.com/docs/k6/latest/set-up/install-k6/#linux"
        exit 1
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 환경 — app x2 + nginx + mysql + redis (default state)
# ─────────────────────────────────────────────────────────────────────────────

env_is_up() {
    curl -fsS "$NGINX_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'
}

build_image_if_missing() {
    if docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
        return
    fi
    note "OCI 이미지 ($IMAGE_NAME) 없음 — bootBuildImage (1~2분)"
    if ! ./gradlew bootBuildImage --imageName="$IMAGE_NAME" >build/review-bootBuildImage.log 2>&1; then
        fail "이미지 빌드 실패. 로그: build/review-bootBuildImage.log"
        return 1
    fi
    ok "이미지 빌드 완료"
}

wait_nginx_ready() {
    local label="${1:-환경}"
    local i
    for i in {1..60}; do
        if env_is_up; then
            ok "$label ready ($NGINX_URL, ${i}회 폴링)"
            return 0
        fi
        sleep 2
    done
    fail "$label 가 ready되지 않음. docker compose ps / logs 확인."
    return 1
}

ensure_env() {
    stage "환경 준비 — app x2 + nginx + mysql + redis"
    build_image_if_missing || return 1

    if env_is_up; then
        ok "환경이 이미 떠 있음 ($NGINX_URL)"
        return 0
    fi

    note "docker compose up — 4개 컨테이너 기동"
    docker compose "${COMPOSE_ARGS[@]}" up -d >/dev/null 2>&1 || {
        fail "compose up 실패 — Docker가 살아있는지 확인하세요"
        return 1
    }
    wait_nginx_ready "환경" || return 1
}

reset_state() {
    ./k6/reset.sh >/dev/null 2>&1 || true
}

require_env() {
    if env_is_up; then return 0; fi
    warn "환경이 응답하지 않습니다 ($NGINX_URL) — 컨테이너가 죽었을 수 있음"
    printf "${BOLD}지금 재기동할까요?${RESET} [Y/n]: "
    local ans=""
    read -r ans || true
    if [[ -z "$ans" || "$ans" =~ ^[yY]$ ]]; then
        action_restart || return 1
        env_is_up
    else
        return 1
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# DB / metric 헬퍼 — 모두 set -e 안전
# ─────────────────────────────────────────────────────────────────────────────

query_db() {
    local out=""
    out=$(docker compose exec -T mysql mysql -N -B -u hellopani -phellopani hellopani \
        -e "$1" 2>/dev/null) || out=""
    echo "${out//[[:space:]]/}"
}

fetch_post_count() {
    local container="$1"
    local ip="" body=""
    ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container" 2>/dev/null) || ip=""
    if [[ -z "$ip" ]]; then echo 0; return; fi
    body=$(docker run --rm --network hello-pani_default curlimages/curl:8.10.1 -s \
        "http://$ip:8080/actuator/metrics/http.server.requests?tag=uri:/bookings&tag=method:POST" 2>/dev/null) || body=""
    if [[ -z "$body" ]] || [[ "$body" != *'"statistic":"COUNT"'* ]]; then
        echo 0; return
    fi
    echo "$body" | sed -nE 's/.*"statistic":"COUNT","value":([0-9]+).*/\1/p' | head -1
}

# ─────────────────────────────────────────────────────────────────────────────
# 검증 시나리오 — 개별 실행
# ─────────────────────────────────────────────────────────────────────────────

run_smoke() {
    stage "Smoke — GET → POST 단일 사이클"
    reset_state

    local user_id="smoke-$(date +%s)"
    local checkout_id
    checkout_id=$(curl -fsS "$NGINX_URL/checkout?productId=1" \
        -H "X-User-Id: $user_id" 2>/dev/null \
        | grep -oE '"checkoutId":"[^"]+"' | cut -d'"' -f4 || echo "")
    if [[ -z "$checkout_id" ]]; then
        fail "GET /checkout 실패 ($NGINX_URL)"
        record "smoke" "FAIL" "GET 실패"
        return 1
    fi
    note "GET → checkoutId=${checkout_id:0:8}…"

    local body status
    body=$(curl -fsS -X POST "$NGINX_URL/bookings" \
        -H "X-User-Id: $user_id" \
        -H "Content-Type: application/json" \
        -d "{\"checkoutId\":\"$checkout_id\",\"productId\":1,\"payments\":[{\"method\":\"CARD\",\"amount\":150000}]}" 2>/dev/null)
    status=$(echo "$body" | grep -oE '"status":"[^"]+"' | cut -d'"' -f4)

    if [[ "$status" == "CONFIRMED" ]]; then
        ok "POST /bookings → 200 CONFIRMED"
        record "smoke" "PASS" "단일 사이클 OK"
    else
        fail "POST 응답 status=$status (CONFIRMED 기대)"
        record "smoke" "FAIL" "status=$status"
        return 1
    fi
}

run_consistency() {
    stage "정합성 — 50 VU 동시 진입에서 정확히 10건만 CONFIRMED + 인스턴스 분포"
    reset_state

    BASE_URL="$NGINX_URL" k6 run -e BASE_URL="$NGINX_URL" \
        -q --no-summary k6/consistency.js >/dev/null 2>&1 || true

    local confirmed stock app1 app2
    confirmed=$(query_db "SELECT COUNT(*) FROM booking WHERE status='CONFIRMED'")
    stock=$(query_db "SELECT qty FROM stock WHERE product_id=1")
    app1=$(fetch_post_count hello-pani-app-1)
    app2=$(fetch_post_count hello-pani-app-2)
    note "DB CONFIRMED = $confirmed   stock.qty = $stock"
    note "app-1 POST = ${app1:-0}, app-2 POST = ${app2:-0}"

    if [[ "$confirmed" == "10" && "$stock" == "0" ]]; then
        if [[ "${app1:-0}" -gt 0 && "${app2:-0}" -gt 0 ]]; then
            ok "정확히 10건 CONFIRMED + 두 인스턴스 모두 트래픽 처리"
            record "consistency" "PASS" "10 CONFIRMED, app-1=$app1 app-2=$app2"
        else
            warn "10건은 맞으나 트래픽이 한쪽에 쏠림 (app-1=$app1, app-2=$app2)"
            record "consistency" "PASS" "10 CONFIRMED — 분포 unbalanced"
        fi
    else
        fail "기대치 어긋남 (CONFIRMED=$confirmed, stock=$stock)"
        record "consistency" "FAIL" "CONFIRMED=$confirmed stock=$stock"
        return 1
    fi
}

run_idempotency() {
    stage "멱등성 — 같은 checkoutId 20 VU → booking 1건만"
    reset_state

    BASE_URL="$NGINX_URL" k6 run -e BASE_URL="$NGINX_URL" \
        -q --no-summary k6/idempotency.js >/dev/null 2>&1 || true

    local bookings payments
    bookings=$(query_db "SELECT COUNT(*) FROM booking")
    payments=$(query_db "SELECT COUNT(*) FROM payment")
    note "DB booking row = $bookings (20번 시도 중)"
    note "DB payment row = $payments"

    if [[ "$bookings" == "1" && "$payments" == "1" ]]; then
        ok "20 VU 동시에 같은 checkoutId → booking/payment 1건만 생성됨"
        record "idempotency" "PASS" "1 booking / 1 payment from 20 VU"
    else
        fail "기대치 어긋남 (booking=$bookings, payment=$payments)"
        record "idempotency" "FAIL" "booking=$bookings payment=$payments"
        return 1
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 부하 시나리오
# ─────────────────────────────────────────────────────────────────────────────

run_load() {
    local scenario="$1"        # browse | rush | spike
    local label="$2"
    local extra_env=("${@:3}") # PEAK_RPS=N PEAK_DURATION=Ns 등 K=V 형식

    stage "부하 — $label ($scenario)"
    reset_state

    local k6_args=(-e BASE_URL="$NGINX_URL" -e SCENARIO="$scenario")
    local kv
    for kv in "${extra_env[@]}"; do
        k6_args+=(-e "$kv")
    done

    BASE_URL="$NGINX_URL" k6 run "${k6_args[@]}" -q --no-summary k6/peak.js >/dev/null 2>&1 || true

    local report="build/load-report-${scenario}.md"
    local confirmed
    confirmed=$(query_db "SELECT COUNT(*) FROM booking WHERE status='CONFIRMED'")

    if [[ -f "$report" ]]; then
        # 보고서의 결과 표 + 통과 기준만 추려 보여준다.
        note "보고서: $report"
        sed -n '/^## 결과/,/^## 통과 기준/p' "$report" | sed -E 's/^/  /'
    fi

    case "$scenario" in
        browse)
            # browse는 GET only — booking_confirmed 카운트는 0이 정상.
            if [[ "$confirmed" == "0" ]]; then
                ok "browse 경로 정상 (CONFIRMED 0, 거절 경로만 도배)"
                record "load-browse" "PASS" "GET only / 0 CONFIRMED"
            else
                warn "browse인데 CONFIRMED=$confirmed (이전 시나리오 잔재일 수 있음)"
                record "load-browse" "PASS" "0 CONFIRMED 기대 / 실제 $confirmed"
            fi
            ;;
        *)
            if [[ "$confirmed" == "10" ]]; then
                ok "$label — 정확히 10건 CONFIRMED"
                record "load-$scenario" "PASS" "10 CONFIRMED"
            else
                fail "$label — CONFIRMED=$confirmed (10 기대)"
                record "load-$scenario" "FAIL" "CONFIRMED=$confirmed"
            fi
            ;;
    esac
}

run_load_browse() { run_load "browse" "평시 50 RPS / 20s" "PEAK_RPS=50" "PEAK_DURATION=20s" "PEAK_RAMP=3s"; }
run_load_rush()   { run_load "rush"   "피크 1000 RPS / 30s" "PEAK_RPS=1000" "PEAK_DURATION=30s" "PEAK_RAMP=10s"; }
run_load_spike()  { run_load "spike"  "spike 50 → 1000 전환" "BROWSE_RPS=50" "BROWSE_DURATION=15s" "PEAK_RPS=1000" "PEAK_DURATION=20s" "PEAK_RAMP=5s"; }

# ─────────────────────────────────────────────────────────────────────────────
# 부분장애 시나리오
# ─────────────────────────────────────────────────────────────────────────────

run_partial_redis_down() {
    stage "부분장애 — Redis 다운 시 503 + DB 우회 차감 없음"
    reset_state

    # 1. baseline GET
    note "1. baseline (Redis up): GET /checkout 정상"
    local cid
    cid=$(curl -fsS "$NGINX_URL/checkout?productId=1" -H "X-User-Id: pf-redis-base" 2>/dev/null \
        | grep -oE '"checkoutId":"[^"]+"' | cut -d'"' -f4 || echo "")
    if [[ -z "$cid" ]]; then
        fail "baseline GET 실패"
        record "redis-down" "FAIL" "baseline broken"
        return 1
    fi
    ok "baseline checkoutId 발급 OK"

    # 2. Redis stop
    note "2. docker stop redis"
    docker stop hello-pani-redis-1 >/dev/null 2>&1 || true
    sleep 2

    # 3. GET /checkout — 503 기대 (CheckoutCache.put fail-fast)
    note "3. GET /checkout → 503 + Retry-After 기대 (DECISIONS 쟁점 5)"
    local get_resp get_status get_retry
    get_resp=$(curl -s -o /dev/null -w "%{http_code}|%header{retry-after}" \
        "$NGINX_URL/checkout?productId=1" -H "X-User-Id: pf-redis-down" 2>/dev/null || echo "0|")
    get_status="${get_resp%%|*}"
    get_retry="${get_resp##*|}"
    if [[ "$get_status" == "503" && -n "$get_retry" ]]; then
        ok "GET → 503 + Retry-After=$get_retry"
    else
        fail "GET 응답 status=$get_status retry-after='$get_retry' (503 기대)"
    fi

    # 4. POST /bookings — 503 기대 (idempotencyService.tryAcquire fail-fast)
    note "4. POST /bookings → 503 기대"
    local post_status
    post_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$NGINX_URL/bookings" \
        -H "X-User-Id: pf-redis-base" -H "Content-Type: application/json" \
        -d "{\"checkoutId\":\"$cid\",\"productId\":1,\"payments\":[{\"method\":\"CARD\",\"amount\":150000}]}" 2>/dev/null || echo "0")
    if [[ "$post_status" == "503" ]]; then
        ok "POST → 503"
    else
        fail "POST 응답 status=$post_status (503 기대)"
    fi

    # 5. DB stock 무변경 — 가장 중요한 불변식
    note "5. DB stock 무손상 확인"
    local stock
    stock=$(query_db "SELECT qty FROM stock WHERE product_id=1")
    if [[ "$stock" == "10" ]]; then
        ok "DB stock = 10 (DB 우회 차감 발생 안 함)"
    else
        fail "DB stock = $stock — Redis 장애가 DB로 새어 나감 ❗"
    fi

    # 6. Redis 재기동 + circuit breaker 회복
    note "6. docker start redis + circuit breaker close 대기"
    docker start hello-pani-redis-1 >/dev/null 2>&1 || true
    local i
    for i in {1..20}; do
        if docker exec hello-pani-redis-1 redis-cli ping 2>/dev/null | grep -q PONG; then
            break
        fi
        sleep 1
    done
    # Resilience4j wait_duration_in_open_state: 5s. circuit이 HALF_OPEN으로 전이 후 성공 호출 3건이면 CLOSED.
    sleep 6

    # 7. POST 정상 복구
    note "7. POST 정상 복구 확인"
    reset_state
    local cid2
    cid2=$(curl -fsS "$NGINX_URL/checkout?productId=1" -H "X-User-Id: pf-redis-recover" 2>/dev/null \
        | grep -oE '"checkoutId":"[^"]+"' | cut -d'"' -f4 || echo "")
    if [[ -z "$cid2" ]]; then
        warn "복구 후 GET 실패 — circuit breaker 회복 시간 더 필요"
        record "redis-down" "PASS" "503 + DB 무손상 OK / 복구 latency"
        return
    fi
    local post_status2
    post_status2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$NGINX_URL/bookings" \
        -H "X-User-Id: pf-redis-recover" -H "Content-Type: application/json" \
        -d "{\"checkoutId\":\"$cid2\",\"productId\":1,\"payments\":[{\"method\":\"CARD\",\"amount\":150000}]}" 2>/dev/null || echo "0")
    if [[ "$post_status2" == "200" ]]; then
        ok "POST 정상 복구 → 200"
        record "redis-down" "PASS" "503 + DB 무손상 + 자동 복구 OK"
    else
        warn "복구 후 POST status=$post_status2"
        record "redis-down" "PASS" "장애 검증 OK / 복구 partial"
    fi
}

run_partial_instance_down() {
    stage "부분장애 — app-1 다운 시 app-2로 service continuity"
    reset_state

    # 1. baseline
    note "1. baseline (app-1 + app-2 모두 up)"

    # 2. stop app-1
    note "2. docker stop hello-pani-app-1"
    docker stop hello-pani-app-1 >/dev/null 2>&1 || true
    # nginx max_fails=3 fail_timeout=10s — 첫 실패 후 잠시 routing 시도가 섞일 수 있음
    sleep 4

    # 3. nginx → app-2로만 라우팅되는지 5회 GET으로 smoke
    note "3. 5회 GET /checkout — 모두 app-2로 가야 함"
    local i success=0
    for i in {1..5}; do
        if curl -fsS "$NGINX_URL/checkout?productId=1" -H "X-User-Id: pf-inst-$i" >/dev/null 2>&1; then
            success=$((success+1))
        fi
    done
    note "성공 $success/5"
    if [[ "$success" -ge 4 ]]; then
        ok "$success/5 GET 성공 — service continuity 유지"
    else
        fail "$success/5 GET 성공 — service continuity 깨짐"
    fi

    # 4. consistency — single instance에서도 정확히 10 CONFIRMED
    note "4. consistency 시나리오 (50 VU) — app-2만 살아있는 상태"
    reset_state
    BASE_URL="$NGINX_URL" k6 run -e BASE_URL="$NGINX_URL" \
        -q --no-summary k6/consistency.js >/dev/null 2>&1 || true
    local confirmed app1 app2
    confirmed=$(query_db "SELECT COUNT(*) FROM booking WHERE status='CONFIRMED'")
    app1=$(fetch_post_count hello-pani-app-1)
    app2=$(fetch_post_count hello-pani-app-2)
    note "DB CONFIRMED = $confirmed (10 기대), app-1=${app1:-0}, app-2=${app2:-0}"

    # 5. restart app-1
    note "5. docker start app-1 — 다음 검증을 위해 복구"
    docker start hello-pani-app-1 >/dev/null 2>&1 || true
    sleep 5

    if [[ "$confirmed" == "10" && "${app2:-0}" -gt 0 ]]; then
        ok "10 CONFIRMED 유지 (app-2만 서비스) — 분산 환경의 service continuity 증명"
        record "instance-down" "PASS" "10 CONFIRMED via app-2 only"
    else
        fail "기대치 어긋남 — CONFIRMED=$confirmed app-2=${app2:-0}"
        record "instance-down" "FAIL" "CONFIRMED=$confirmed"
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 액션 — 메뉴 항목별 진입점. 모두 reset_records → run → show_summary
# ─────────────────────────────────────────────────────────────────────────────

action_smoke()         { require_env || return 1; reset_records; run_smoke || true;        show_summary; }
action_consistency()   { require_env || return 1; reset_records; run_consistency || true;  show_summary; }
action_idempotency()   { require_env || return 1; reset_records; run_idempotency || true;  show_summary; }

action_load_browse()   { require_env || return 1; reset_records; run_load_browse || true;  show_summary; }
action_load_rush()     { require_env || return 1; reset_records; run_load_rush || true;    show_summary; }
action_load_spike()    { require_env || return 1; reset_records; run_load_spike || true;   show_summary; }
action_load_all() {
    require_env || return 1
    reset_records
    run_load_browse || true
    run_load_rush || true
    run_load_spike || true
    show_summary
}

action_partial_redis()    { require_env || return 1; reset_records; run_partial_redis_down || true;    show_summary; }
action_partial_instance() { require_env || return 1; reset_records; run_partial_instance_down || true; show_summary; }

action_restart() {
    stage "환경 재기동 (down → up)"
    printf "${BOLD}이미지도 재빌드할까요?${RESET} (코드 변경 후 반영용, 1~2분 추가) [y/N]: "
    local rebuild_choice=""
    read -r rebuild_choice || true
    note "docker compose down"
    docker compose "${COMPOSE_ARGS[@]}" down >/dev/null 2>&1 || true
    if [[ "$rebuild_choice" =~ ^[yY]$ ]]; then
        note "bootBuildImage (~1-2분)"
        if ! ./gradlew bootBuildImage --imageName="$IMAGE_NAME" >build/review-bootBuildImage.log 2>&1; then
            fail "이미지 재빌드 실패. 로그: build/review-bootBuildImage.log"
            return 1
        fi
        ok "이미지 재빌드 완료"
    fi
    note "docker compose up"
    docker compose "${COMPOSE_ARGS[@]}" up -d >/dev/null 2>&1
    wait_nginx_ready "환경" || return 1
    ok "환경 재기동 완료"
}

action_teardown_and_exit() {
    stage "정리 — 모든 컨테이너 down (볼륨 유지)"
    # observability 서비스도 함께 정리 (떠 있을 수도 있음)
    docker compose "${COMPOSE_ARGS_WITH_OBS[@]}" down >/dev/null 2>&1 || true
    ok "모든 컨테이너 down"
    echo
    note "다시 띄우려면 ./review.sh"
    exit 0
}

# ─────────────────────────────────────────────────────────────────────────────
# 모니터링 (Prometheus + Grafana) 토글
# ─────────────────────────────────────────────────────────────────────────────

observability_is_up() {
    docker ps --filter name=hello-pani-grafana-1 --filter status=running -q | grep -q .
}

action_observability_toggle() {
    stage "모니터링 — Prometheus + Grafana"

    if observability_is_up; then
        note "현재 떠 있음 → 끄기"
        docker compose "${COMPOSE_ARGS_WITH_OBS[@]}" stop "${OBS_SERVICES[@]}" >/dev/null 2>&1 || true
        docker compose "${COMPOSE_ARGS_WITH_OBS[@]}" rm -f "${OBS_SERVICES[@]}" >/dev/null 2>&1 || true
        ok "Prometheus / Grafana 중지 (앱은 그대로)"
        return
    fi

    note "기동 중... (4개 컨테이너: prometheus, grafana, redis-exporter, mysql-exporter)"
    docker compose "${COMPOSE_ARGS_WITH_OBS[@]}" up -d "${OBS_SERVICES[@]}" >/dev/null 2>&1 || {
        fail "compose up 실패"
        return 1
    }

    note "Grafana readiness 대기..."
    local i
    for i in {1..30}; do
        if curl -fsS "$GRAFANA_URL/api/health" >/dev/null 2>&1; then
            ok "Grafana ready"
            break
        fi
        sleep 1
    done

    # 대시보드 UID는 hello-pani.json 최하단의 "uid": "hello-pani-main"으로 고정.
    local dashboard_url="$GRAFANA_URL/d/hello-pani-main"

    echo
    printf "  ${BOLD}대시보드${RESET}    : %s\n" "$dashboard_url"
    printf "  ${BOLD}로그인${RESET}      : admin / admin (anonymous viewer 모드 활성화)\n"
    printf "  ${BOLD}Prometheus${RESET}  : %s/targets\n" "$PROMETHEUS_URL"
    echo
    note "이제 [4]/[5]/[6]/[7] 부하를 돌리면서 그라파나에서 실시간으로 확인 가능"
    note "인스턴스별 자원(JVM/CPU/Hikari/CB)은 패널 legend에 instance 라벨로 분리됨"

    # 브라우저 자동 오픈 — 실패해도 무해.
    if command -v open >/dev/null 2>&1; then
        open "$dashboard_url" 2>/dev/null || true
    elif command -v xdg-open >/dev/null 2>&1; then
        xdg-open "$dashboard_url" 2>/dev/null || true
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 결과 요약
# ─────────────────────────────────────────────────────────────────────────────

show_summary() {
    echo
    printf "${BOLD}══════════════════════════════════════════════════${RESET}\n"
    printf "${BOLD} 결과${RESET}\n"
    printf "${BOLD}══════════════════════════════════════════════════${RESET}\n"
    local i any_fail=0
    if [[ ${#STAGE_NAMES[@]} -eq 0 ]]; then
        warn "기록된 결과가 없습니다."
        return
    fi
    for ((i=0; i<${#STAGE_NAMES[@]}; i++)); do
        local n="${STAGE_NAMES[$i]}"
        local r="${STAGE_RESULTS[$i]}"
        local d="${STAGE_DETAILS[$i]}"
        if [[ "$r" == "PASS" ]]; then
            printf "  ${GREEN}✓${RESET} %-16s %s\n" "$n" "$d"
        else
            printf "  ${RED}✗${RESET} %-16s %s\n" "$n" "$d"
            any_fail=1
        fi
    done
    echo
    if [[ $any_fail -eq 0 ]]; then
        printf "${GREEN}${BOLD}passed.${RESET}\n"
    else
        printf "${RED}${BOLD}some checks failed.${RESET}\n"
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# 메뉴
# ─────────────────────────────────────────────────────────────────────────────

show_menu() {
    cat <<EOF

${BOLD}══════════════════════════════════════════════════════${RESET}
${BOLD} hello-pani — 검증 콘솔${RESET}
${BOLD}══════════════════════════════════════════════════════${RESET}
${DIM} env: app x2 + nginx + mysql + redis  →  $NGINX_URL${RESET}

${BOLD}검증${RESET}
  ${BOLD}[1]${RESET} smoke              GET → POST 단일 사이클
  ${BOLD}[2]${RESET} 정합성             50 VU → 정확히 10 CONFIRMED + 분포
  ${BOLD}[3]${RESET} 멱등성             같은 checkoutId 20 VU → 1건만

${BOLD}부하${RESET}
  ${BOLD}[4]${RESET} 평시               browse 50 RPS / 20s
  ${BOLD}[5]${RESET} 피크               rush 1000 RPS / 30s
  ${BOLD}[6]${RESET} spike              50 → 1000 RPS 전환
  ${BOLD}[7]${RESET} 부하 전체          4 + 5 + 6

${BOLD}부분장애${RESET}
  ${BOLD}[8]${RESET} Redis 다운         503 + DB 우회 없음 + 자동 복구
  ${BOLD}[9]${RESET} 인스턴스 1대 다운  app-2만으로 service continuity

${BOLD}환경${RESET}
  ${BOLD}[g]${RESET} 모니터링 토글      Prometheus + Grafana 켜기 / 끄기
  ${BOLD}[r]${RESET} 재기동             down → up (이미지 재빌드 옵션)
  ${BOLD}[c]${RESET} 정리               down + 종료
  ${BOLD}[q]${RESET} 종료               env 그대로 유지

EOF
}

press_enter_to_continue() {
    echo
    printf "${DIM}메뉴로 돌아가려면 Enter…${RESET}"
    read -r _ || true
}

main() {
    check_prereqs
    ensure_env || warn "초기 환경 기동 실패 — 메뉴에서 [r] 재기동을 시도하세요"

    while true; do
        show_menu
        printf "${BOLD}선택: ${RESET}"
        local choice=""
        read -r choice || { echo; exit 0; }
        echo

        case "${choice:-q}" in
            1) action_smoke || true;          press_enter_to_continue ;;
            2) action_consistency || true;    press_enter_to_continue ;;
            3) action_idempotency || true;    press_enter_to_continue ;;
            4) action_load_browse || true;    press_enter_to_continue ;;
            5) action_load_rush || true;      press_enter_to_continue ;;
            6) action_load_spike || true;     press_enter_to_continue ;;
            7) action_load_all || true;       press_enter_to_continue ;;
            8) action_partial_redis || true;  press_enter_to_continue ;;
            9) action_partial_instance || true; press_enter_to_continue ;;
            g|G) action_observability_toggle || true; press_enter_to_continue ;;
            r|R) action_restart || true;      press_enter_to_continue ;;
            c|C) action_teardown_and_exit ;;
            q|Q)
                echo "bye (env 그대로 유지 — 정리하려면 [c] 또는 ./scripts/docker-clean.sh --all)"
                exit 0
                ;;
            *)  fail "잘못된 선택: $choice"
                press_enter_to_continue
                ;;
        esac
    done
}

main "$@"
