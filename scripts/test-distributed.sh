#!/usr/bin/env bash
#
# 분산 환경 데모 — app x2 + nginx 라운드로빈으로 같은 부하를 돌려 정확히 10건만 CONFIRMED인지 확인.
#
# 검증 의도:
# - "정말 stateless하게 작성됐는가" — 같은 사용자가 어느 인스턴스로 라우팅돼도 결과가 같다.
# - "수평 확장에 고통이 없는가" — 두 번째 인스턴스를 띄우는 데 별도 설정/마이그레이션이 없다.
# - DECISIONS.md 쟁점 10의 walk-the-talk.
#
# 사용:
#   ./scripts/test-distributed.sh           # 처음이면 bootBuildImage 자동 수행
#   FORCE_REBUILD=true ./scripts/test-distributed.sh  # 이미지 강제 재빌드

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "${ROOT_DIR}/scripts/_common.sh"

require_command docker
require_command k6

IMAGE_NAME="hello-pani:0.0.1-SNAPSHOT"
NGINX_URL="http://localhost:18080"
COMPOSE_ARGS=(-f docker-compose.yml -f docker-compose.scale.yml)

echo "=================================================="
echo "분산 환경 데모 — app x2 + nginx 라운드로빈"
echo "=================================================="
echo

# 1. bootRun이 8080을 점유 중이면 mysql/redis lifecycle 충돌이 날 수 있다 — 먼저 안내.
if curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
    echo "경고: 호스트 8080에 다른 앱(예: ./gradlew bootRun)이 떠 있습니다."
    echo "      이 스크립트는 docker app x2를 8081(nginx) 뒤에 띄우므로 충돌은 없지만,"
    echo "      mysql/redis 컨테이너 lifecycle이 bootRun과 분리됩니다."
    echo
fi

# 2. 이미지 빌드 — 없거나 FORCE_REBUILD=true면 새로 만든다.
if [[ "${FORCE_REBUILD:-false}" == "true" ]] || ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    echo "[1/5] $IMAGE_NAME 이미지가 없거나 강제 재빌드 — bootBuildImage 실행 (1~2분 소요)"
    ./gradlew bootBuildImage --imageName="$IMAGE_NAME"
    echo
else
    echo "[1/5] $IMAGE_NAME 이미지 재사용 (FORCE_REBUILD=true로 강제 가능)"
    echo
fi

# 3. compose up. 기존 bootRun이 띄운 mysql/redis가 있으면 그대로 재사용된다.
echo "[2/5] compose up (app-1, app-2, nginx)..."
docker compose "${COMPOSE_ARGS[@]}" up -d

echo "  nginx healthcheck 대기..."
for _ in {1..60}; do
    if curl -fsS "$NGINX_URL/actuator/health" >/dev/null 2>&1; then
        echo "  ready: $NGINX_URL"
        break
    fi
    sleep 2
done
if ! curl -fsS "$NGINX_URL/actuator/health" >/dev/null 2>&1; then
    echo "  타임아웃: nginx 또는 app 인스턴스가 health UP에 도달하지 못함" >&2
    docker compose "${COMPOSE_ARGS[@]}" logs --tail=80 nginx app-1 app-2 >&2
    exit 1
fi
echo

# 4. 상태 초기화 — stock=10으로 되돌림.
echo "[3/5] DB / Redis 상태 초기화 (stock=10)..."
./k6/reset.sh
echo

# 5. consistency 시나리오를 nginx 뒤로 보낸다 — 50 VU 동시 진입 → 정확히 10건만 CONFIRMED.
echo "[4/5] consistency 시나리오 실행 (target=$NGINX_URL)..."
BASE_URL="$NGINX_URL" k6 run -e BASE_URL="$NGINX_URL" k6/consistency.js
echo

# 6. 트래픽 분산 검증 — actuator metric으로 인스턴스별 POST /bookings 카운트 비교.
# (Tomcat 기본은 access log를 끄고 있어 docker logs grep으론 안 보인다.)
echo "[5/5] 인스턴스별 트래픽 확인 (actuator metrics)..."
fetch_post_count() {
    local container="$1"
    local ip
    ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container" 2>/dev/null)
    if [[ -z "$ip" ]]; then echo 0; return; fi
    local body
    body=$(docker run --rm --network hello-pani_default curlimages/curl:8.10.1 -s \
        "http://$ip:8080/actuator/metrics/http.server.requests?tag=uri:/bookings&tag=method:POST" 2>/dev/null)
    if [[ -z "$body" ]] || [[ "$body" != *'"statistic":"COUNT"'* ]]; then
        echo 0
        return
    fi
    # "statistic":"COUNT","value":26.0 → 26
    echo "$body" | sed -nE 's/.*"statistic":"COUNT","value":([0-9]+).*/\1/p' | head -1
}
APP1_BOOKING=$(fetch_post_count hello-pani-app-1)
APP2_BOOKING=$(fetch_post_count hello-pani-app-2)
echo "  app-1 POST /bookings count: ${APP1_BOOKING:-0}"
echo "  app-2 POST /bookings count: ${APP2_BOOKING:-0}"

CONFIRMED=$(docker compose exec -T mysql mysql -N -B -u hellopani -phellopani hellopani \
    -e "SELECT COUNT(*) FROM booking WHERE status='CONFIRMED'" 2>/dev/null | tr -d '[:space:]' || echo "?")
STOCK=$(docker compose exec -T mysql mysql -N -B -u hellopani -phellopani hellopani \
    -e "SELECT qty FROM stock WHERE product_id=1" 2>/dev/null | tr -d '[:space:]' || echo "?")

echo
echo "=================================================="
echo "결과 요약"
echo "=================================================="
echo "  booking CONFIRMED count = $CONFIRMED   (기대: 10)"
echo "  stock.qty                = $STOCK      (기대: 0)"
echo "  app-1 POST /bookings     = ${APP1_BOOKING:-0}"
echo "  app-2 POST /bookings     = ${APP2_BOOKING:-0}"
echo

if [[ "$CONFIRMED" == "10" && "$STOCK" == "0" ]]; then
    echo "✅ 분산 환경에서도 정확히 10건만 CONFIRMED. stateless / 수평 확장 가정 통과."
    EXIT_CODE=0
else
    echo "❌ 정합성 위반. 위 카운트 확인하라." >&2
    EXIT_CODE=1
fi

if [[ "${APP1_BOOKING:-0}" -gt 0 && "${APP2_BOOKING:-0}" -gt 0 ]]; then
    echo "✅ 트래픽이 두 인스턴스 모두에 분산됨 — 진짜 stateless / 수평 확장 가능 증명."
elif [[ "${ALLOW_SINGLE_UPSTREAM:-false}" == "true" ]]; then
    echo "ℹ︎ 한쪽 인스턴스에만 트래픽이 갔지만 ALLOW_SINGLE_UPSTREAM=true로 무시."
else
    echo "⚠ 한쪽 인스턴스에 트래픽이 없음. nginx upstream 설정 / health 확인."
    EXIT_CODE=1
fi

echo
echo "정리하려면: ./scripts/docker-clean.sh --scale-only  (또는 --all)"
exit "$EXIT_CODE"
