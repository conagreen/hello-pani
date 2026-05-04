#!/usr/bin/env bash
# k6 시나리오 실행 전 DB / Redis 상태를 초기화한다.
# - DB stock = 10, point_account 잔액 복원
# - 테스트가 만든 booking / payment / checkout / point_ledger / compensation_step 정리
# - Redis 전체 flush 후 DB stock 값으로 stock:{productId} 카운터 재시드
#
# 사용 전제: docker compose로 mysql / redis가 떠 있어야 한다.

set -euo pipefail

cd "$(dirname "$0")/.."

docker compose exec -T mysql mysql -u hellopani -phellopani hellopani <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM compensation_step;
DELETE FROM payment_component;
DELETE FROM payment;
DELETE FROM booking;
DELETE FROM point_ledger;
DELETE FROM checkout;
SET FOREIGN_KEY_CHECKS = 1;
UPDATE stock SET qty = 10 WHERE product_id = 1;
UPDATE point_account SET balance = 50000 WHERE user_id = 'test-user-1';
SQL

docker compose exec -T redis redis-cli FLUSHDB > /dev/null

# DB의 모든 stock row를 Redis 카운터로 재시드한다.
# StockInitializer는 ApplicationReadyEvent 시에만 동작하므로, 앱 재시작 없이 reset만 한 경우 여기서 채워준다.
docker compose exec -T mysql mysql -N -B -u hellopani -phellopani hellopani \
    -e "SELECT product_id, qty FROM stock" |
while read -r product_id qty; do
    docker compose exec -T redis redis-cli SET "stock:${product_id}" "${qty}" > /dev/null
done

echo "reset complete:"
docker compose exec -T mysql mysql -N -B -u hellopani -phellopani hellopani \
    -e "SELECT CONCAT('  stock product_id=', product_id, ' qty=', qty) FROM stock"
echo "  redis stock keys:"
docker compose exec -T redis redis-cli --no-raw KEYS 'stock:*' | sed 's/^/    /'
