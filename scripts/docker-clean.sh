#!/usr/bin/env bash
#
# Docker 컨테이너 / 볼륨 정리 스크립트.
#
# 기본 동작 (인자 없음): hello-pani 관련 컨테이너만 정지 + 제거. 볼륨은 유지.
# --volumes / -v       : 볼륨까지 삭제 (MySQL 데이터, Redis 데이터 모두 사라짐)
# --observability-only : Prometheus / Grafana / exporters만 정리 (mysql / redis는 유지)
# --scale-only         : 분산 데모 stack (app-1, app-2, nginx)만 정리
# --all                : observability + scale + base 모두 정리

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MODE="default"
DELETE_VOLUMES=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --volumes|-v)
            DELETE_VOLUMES=true
            ;;
        --observability-only)
            MODE="observability"
            ;;
        --scale-only)
            MODE="scale"
            ;;
        --all)
            MODE="all"
            ;;
        -h|--help)
            sed -n '3,8p' "$0"
            exit 0
            ;;
        *)
            echo "unknown option: $1" >&2
            exit 1
            ;;
    esac
    shift
done

down_args=""
if $DELETE_VOLUMES; then
    down_args="-v"
fi

OBS_SERVICES=(prometheus grafana redis-exporter mysql-exporter)
SCALE_SERVICES=(app-1 app-2 nginx)

case "$MODE" in
    observability)
        echo "stopping observability stack only (${OBS_SERVICES[*]})..."
        # 같은 compose 프로젝트 안의 일부 컨테이너만 정지/제거 (mysql/redis는 유지).
        docker compose -f docker-compose.yml -f docker-compose.observability.yml \
            stop "${OBS_SERVICES[@]}" 2>/dev/null || true
        docker compose -f docker-compose.yml -f docker-compose.observability.yml \
            rm -f "${OBS_SERVICES[@]}" 2>/dev/null || true
        ;;
    scale)
        echo "stopping scale stack only (${SCALE_SERVICES[*]})..."
        docker compose -f docker-compose.yml -f docker-compose.scale.yml \
            stop "${SCALE_SERVICES[@]}" 2>/dev/null || true
        docker compose -f docker-compose.yml -f docker-compose.scale.yml \
            rm -f "${SCALE_SERVICES[@]}" 2>/dev/null || true
        ;;
    all)
        echo "stopping ALL containers (app infra + observability + scale)..."
        docker compose -f docker-compose.yml -f docker-compose.observability.yml -f docker-compose.scale.yml down $down_args 2>/dev/null || true
        ;;
    default)
        # 양쪽 다 정리하지만 mysql/redis 데이터 볼륨은 유지하는 게 기본
        echo "stopping all hello-pani containers (volumes preserved unless --volumes)..."
        docker compose -f docker-compose.yml -f docker-compose.observability.yml -f docker-compose.scale.yml down $down_args 2>/dev/null || true
        ;;
esac

echo
echo "현재 남아 있는 hello-pani 관련 컨테이너:"
docker ps -a --filter "name=hello-pani" --format "  {{.Names}} ({{.Status}})" || true

echo
echo "현재 남아 있는 hello-pani 관련 볼륨:"
docker volume ls --filter "name=hello-pani" --format "  {{.Name}}" || true

if ! $DELETE_VOLUMES; then
    echo
    echo "팁: 데이터까지 완전히 비우려면 ./scripts/docker-clean.sh --volumes"
fi
