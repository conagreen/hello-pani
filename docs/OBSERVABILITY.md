# OBSERVABILITY

이 문서는 Task 8 결과물을 모은다. 메트릭 노출, k6 시나리오 실행 방법, 통과 기준을 정리한다.
README는 Task 9에서 별도로 작성하며, 이 문서는 Task 9 README에서 링크된다.

## 메트릭

Spring Boot Actuator의 `metrics` 엔드포인트로 노출한다.

```bash
curl http://localhost:8080/actuator/metrics
curl http://localhost:8080/actuator/metrics/booking.confirmed
curl http://localhost:8080/actuator/metrics/redis.gate.failure?tag=reason:SOLD_OUT_OR_PROCESSING
```

### 카운터 목록

| 메트릭                          | 태그                                                | 의미                                          |
|------------------------------|---------------------------------------------------|---------------------------------------------|
| `redis.gate.success`         | -                                                 | StockGate.tryAcquire에서 게이트 통과               |
| `redis.gate.failure`         | `reason=SOLD_OUT_OR_PROCESSING`                   | StockGate.tryAcquire에서 게이트 거절               |
| `db.stock.reserve.success`   | -                                                 | DB 조건부 UPDATE로 재고 선점 성공                     |
| `db.stock.reserve.failure`   | `reason=SOLD_OUT`                                 | DB 조건부 UPDATE로 재고 선점 실패                     |
| `booking.confirmed`          | -                                                 | Booking이 CONFIRMED로 마감                      |
| `payment.failure`            | `reason=LIMIT_EXCEEDED|CARD_DECLINED|INSUFFICIENT_POINT` | Payment 확정 실패 분류                            |
| `http.503`                   | `reason=REDIS_UNAVAILABLE`                        | Redis 장애로 booking 요청을 503으로 종결              |
| `compensation.refund_failed` | -                                                 | 보상 단계가 끝까지 실패하여 Payment를 REFUND_FAILED로 마킹 |

태그 cardinality는 enum 수준으로 제한한다. `userId`, `checkoutId`, `productId`처럼 무한 cardinality 값은 태그에 넣지 않는다.

## k6 시나리오

### 사전 조건

- 로컬에 k6 설치: <https://grafana.com/docs/k6/latest/set-up/install-k6/>
- 인프라 기동: `./gradlew bootRun` (Spring Boot Docker Compose가 mysql / redis를 함께 띄움)
- 앱이 `http://localhost:8080`에서 떠 있어야 함

### 실행 전 초기화

각 시나리오 실행 전에 DB stock과 Redis 상태를 초기화한다.

```bash
./k6/reset.sh
```

`reset.sh`는 다음을 수행한다.

1. 테스트가 만든 booking / payment / checkout / point_ledger / compensation_step 정리
2. `stock.qty = 10`, `point_account.balance = 50000` 복원
3. Redis FLUSHDB
4. DB `stock` 테이블의 값으로 Redis `stock:{productId}` 카운터 재시드

`StockInitializer`는 `ApplicationReadyEvent`에서만 시드하므로, 앱을 재시작하지 않은 채 reset만 실행할 때 4번 단계가 필요하다.

### consistency.js — 정확히 10건만 성공

50명의 가상 사용자가 거의 동시에 booking을 시도한다. 최종 CONFIRMED는 정확히 10건이어야 한다.

```bash
./k6/reset.sh
k6 run k6/consistency.js
```

통과 기준 (k6 thresholds 자동 검증):

- `booking_confirmed_total == 10`
- `booking_error_total == 0`

추가 관찰:

- `booking_sold_out_total`은 0보다 크고 `40` 근방이어야 한다 (Redis gate 또는 DB stock에서 거절된 수).

서버측 메트릭 확인:

```bash
curl -s http://localhost:8080/actuator/metrics/booking.confirmed | jq '.measurements'
curl -s http://localhost:8080/actuator/metrics/db.stock.reserve.success | jq '.measurements'
```

### idempotency.js — 같은 checkoutId 중복 요청

20명의 VU가 같은 `checkoutId`로 동시에 booking을 보낸다. 결제 / Booking row는 단 1건만 생성되어야 하고, 나머지는 동일 결과 재생 또는 처리 중 응답으로 돌아온다.

```bash
./k6/reset.sh
k6 run k6/idempotency.js
```

통과 기준 (k6 thresholds 자동 검증):

- `booking_confirmed_total >= 1` (최소 1건은 첫 처리 또는 재생된 CONFIRMED)
- `booking_failed_total == 0`
- `booking_error_total == 0`

DB 검증 (자동 검증 외 수동 확인):

```bash
docker compose exec -T mysql mysql -u hellopani -phellopani hellopani -e "
  SELECT COUNT(*) AS bookings FROM booking;
  SELECT COUNT(*) AS payments FROM payment;
  SELECT qty FROM stock WHERE product_id = 1;
"
```

기대 결과:

- `bookings = 1`
- `payments = 1`
- `stock.qty = 9`

## 환경 변수

두 시나리오 모두 다음 환경 변수를 받는다.

```bash
BASE_URL=http://localhost:8080      # 기본값
PRODUCT_ID=1                         # 기본값
PRICE=150000                         # 기본값 (consistency / idempotency 공통)
USER_ID=k6-idem-user                 # idempotency에서만 사용
```

예시:

```bash
BASE_URL=http://localhost:9090 k6 run k6/consistency.js
```

## 다루지 않은 것

다음은 이 단계에서 구현하지 않는다. README나 시나리오에서 구현된 것처럼 약속하지 않는다.

- PG 응답시간 메트릭: Fake PG 기반 최소 구현에서는 별도 계측하지 않는다.
- 운영자용 잔여 재고 노출: 사용자 API 잔여 재고 미노출 원칙을 유지하기 위해 이번 범위에서는 제공하지 않는다. 필요하면 별도 운영자 조회나 내부 대시보드로 분리한다.
- Prometheus remote-write / Grafana dashboard: 선택 확장이다. 기본 검증은 k6 stdout 요약과 Actuator/Micrometer 메트릭 노출로 충분하다.
- 스파이크 / Redis 장애 자동 시나리오 (`k6/spike.js`, `k6/redis-failure.js`)
- 분산 추적
- 실시간 알림 채널
