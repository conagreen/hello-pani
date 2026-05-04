# DECISIONS

이 문서는 이 프로젝트의 핵심 기술 결정을 짧게 설명한다.
상세 API, 상태, ERD는 [DOMAIN.md](DOMAIN.md)를 보고, 구현 순서는 [TASKS.md](TASKS.md)를 본다.

## 한 줄 요약

한정 재고 10개에 트래픽이 몰릴 때, Redis는 빠른 입장 게이트로만 쓰고 최종 재고 진실은 MySQL 조건부 UPDATE가 가진다.
결제는 DB 재고 선점 이후에만 호출하며, 실패 보상은 checkoutId 기준으로 멱등하게 재실행한다.

## 핵심 불변식

- Redis gate 통과는 예약 성공이 아니라 **DB 재고 선점 시도권**이다.
- 최종 재고는 DB `stock.qty`다.
- 결제는 DB 재고 선점이 성공한 뒤에만 호출한다.
- DB 트랜잭션을 잡은 채 외부 PG를 호출하지 않는다.
- Redis 장애 시 DB 우회 판매를 하지 않고 `503 + Retry-After`로 fail-fast한다.
- PG 결과 불명은 즉시 실패/환불하지 않고 checkoutId 기반 결과 조회를 먼저 한다.
- 사용자 API에는 정확한 잔여 재고 수량을 노출하지 않는다.

---

## 쟁점 1. 재고 정합성

**선택: Redis gate + DB 조건부 UPDATE**

흐름:

1. Redis Lua가 `stock:{productId}`를 원자적으로 차감하고 `hold:{checkoutId}`를 만든다.
2. Redis 통과 요청만 DB 트랜잭션에 들어간다.
3. DB가 `UPDATE stock SET qty = qty - 1 WHERE product_id = ? AND qty > 0`로 최종 선점한다.
4. DB 선점 성공 후 Booking `PENDING_PAYMENT`, Payment `PROCESSING`을 같은 트랜잭션에서 만든다.
5. 트랜잭션을 커밋한 뒤 결제를 호출한다.

왜 이렇게 했는가:

- Redis만 진실로 두면 빠르지만, Redis와 DB 사이의 이기종 정합성 설명이 어려워진다.
- DB만 쓰면 단순하지만 00시 스파이크 대부분이 DB에 닿는다.
- Redis는 대부분의 실패 요청을 앞에서 거르고, DB는 최종 진실을 짧은 조건부 UPDATE로 확정한다.
- 결제 전에 DB 재고를 선점하므로 "결제 성공 후 재고 없음"이 구조적으로 발생하지 않는다.

주의:

- 이 방식은 version 컬럼을 읽어 비교하는 낙관적 락이 아니다.
- Redis gate 통과자는 성공자가 아니라 후보자다.
- Redis 장애나 결과 불명처럼 안전하게 판매 가능 여부를 판단할 수 없으면 availability보다 correctness를 우선한다.

---

## 쟁점 2. 공정성

**선택: 서버 Redis gate 도달 순서 기준의 절차적 공정성**

요구사항의 "선착순"과 "동등한 확률"은 엄밀히 같은 말이 아니다.
이 프로젝트는 추첨 모델이 아니라, 서버 gate에 도달한 이후 모든 요청에 같은 규칙을 적용하는 절차적 공정성으로 해석한다.

보장하는 것:

- 단일 Redis Lua 실행 순서 기준으로 먼저 처리된 요청이 먼저 후보권을 얻는다.
- 애플리케이션 인스턴스별 로컬 큐나 메모리 재고를 두지 않는다.

보장하지 않는 것:

- 사용자 네트워크 RTT의 동등성
- 실패한 사용자를 위한 대기열
- 보상으로 복구된 재고를 과거 실패자에게 배정하는 정책

복구된 재고는 별도 알림이나 예약 없이, 이후 다시 Redis gate에 도달한 요청이 획득한다.

---

## 쟁점 3. 멱등성

**선택: 서버가 발급한 checkoutId를 POST Booking 멱등키로 사용**

정책:

- GET Checkout은 비멱등이다. 호출할 때마다 새 checkoutId를 발급한다.
- POST Booking은 checkoutId 기준으로 멱등이다.
- Redis `SETNX idempotency:{checkoutId}`가 중복 실행을 조기 차단한다.
- DB에도 `booking.checkout_id`, `payment.checkout_id` unique 제약을 둔다.
- 처리 완료 결과는 `idempotency:result:{checkoutId}`에 24시간 캐시한다.

검증 실패 처리:

- Checkout 사용자 불일치, 만료, 결제 조합 오류처럼 재고 gate 이전 실패는 idempotency key를 삭제한다.
- DB 재고 선점 이후 실패는 Booking/Payment/보상 상태를 남기고 같은 결과를 재생한다.
- `RESULT_PENDING`이 나중에 확정되면 결과 조회 잡이 idempotency 캐시를 최종 결과로 갱신한다.

### Checkout 영속화 — Redis 임시 매핑 + booking 시점 DB INSERT

GET /checkout이 *DB write를 동반하면* 거절될 운명의 트래픽이 모두 DB INSERT 한 줄을 만들고 떠나, "Redis 게이트로 부하를 막는다"는 약속이 GET 단계에서 무너진다. 이를 피하기 위해 Checkout은 두 단계로 나눠 영속화한다.

1. **GET /checkout** — `checkout:{id} = userId` 매핑 하나만 Redis에 적재한다 (TTL 10분).
    - 가격 / 포인트 잔액 / 만료 등은 Redis에 저장하지 않는다.
    - 만료는 Redis TTL이 자동 처리 (cache miss = 만료된 것으로 본다).
    - Redis 부담은 최소화 — 30만 GET이 와도 30만 × 36바이트 = 약 12MB.
2. **POST /bookings, gate 통과 시점** — `BookingService.reserveInTransaction` 안에서 `INSERT INTO checkout`을 booking / payment INSERT와 같은 트랜잭션으로 수행한다.
    - 가격(`quoted_price`)과 포인트 잔액(`available_point_snapshot`)은 product / point_account를 *POST 시점에 재조회*해 채운다. GET 시점 캡처가 강한 약속은 아니지만 한정 이벤트 도메인에서는 가격 변동이 거의 없어 실용적으로 충분하다.
    - FK 정합성 유지 (`booking.checkout_id`, `payment.checkout_id`).

결과 — 거절 경로의 DB hit은 0. booking 시점에야 비로소 checkout / booking / payment가 같이 INSERT된다. "거절을 빠르게"가 GET 단계까지 일관된다.

---

## 쟁점 4. 결제 확장성

**선택: PaymentMethod + PaymentComposer + PaymentValidator**

역할:

- `PaymentValidator`: 허용 조합과 금액 합계를 검증한다.
- `PaymentMethod`: 카드, Y페이, 포인트 각각의 charge/refund 전략이다.
- `PaymentComposer`: 여러 결제 수단을 순차 실행하고, 확정 실패 시 성공한 component를 역순 보상한다.

허용 조합:

- `CARD`
- `Y_PAY`
- `POINT`
- `CARD + POINT`
- `Y_PAY + POINT`

금지 조합:

- `CARD + Y_PAY`
- 같은 결제 수단 중복

이 구조는 새 결제 수단을 추가할 때 Booking 흐름 수정을 최소화하기 위한 선택이다.

---

## 쟁점 5. Redis 장애

**선택: 단일 Redis 인스턴스 + fail-fast**

Redis는 처리량 확장보다 재고 gate의 직렬화가 핵심이다.
Redis Cluster/Sentinel은 운영 가용성 보강 옵션이지만, 기본 범위에서는 단일 Redis를 사용한다.

장애 정책:

- Redis timeout은 200ms로 짧게 둔다.
- Resilience4j circuit breaker로 장애가 지속되면 즉시 실패한다.
- 사용자 응답은 `503 Service Unavailable` + `Retry-After`다.
- Redis 장애 시 DB로 우회 차감하지 않는다.

이 선택은 일부 재고가 남아도 판매를 멈출 수 있다.
이는 초과 판매 방지를 우선하는 보수적 실패다.

---

## 쟁점 6. 결제 실패와 보상

**선택: 확정 실패와 결과 불명을 분리**

분류:

| 케이스 | 처리 |
|---|---|
| 한도 초과 | 확정 실패. 보상 실행 |
| 카드 거절 | 확정 실패. 보상 실행 |
| 포인트 부족 | 확정 실패. PG 호출 전 실패 |
| PG 타임아웃 | `RESULT_PENDING`. checkoutId로 결과 조회 |
| PG 응답 미수신 | `RESULT_PENDING`. checkoutId로 결과 조회 |

상태 흐름:

```text
PROCESSING
  -> SUCCEEDED
  -> RESULT_PENDING -> SUCCEEDED | FAILED
  -> FAILED -> COMPENSATING -> COMPENSATED
                              -> REFUND_FAILED
```

보상 단계:

1. `POINT_REFUNDED`
2. `DB_STOCK_RESTORED`
3. `REDIS_GATE_RESTORED`

각 단계는 `compensation_step(checkout_id, step)` unique 제약으로 완료 여부를 기록한다.
중간에 실패해도 같은 checkoutId로 재실행하면 이미 완료된 단계는 건너뛰고 실패한 단계만 다시 시도한다.

---

## 쟁점 7. DB와 도메인 표현

**선택: schema.sql + JDBC + 얇은 도메인 모델**

이 프로젝트의 핵심은 복잡한 객체 그래프가 아니라 재고 정합성, 멱등성, 실패 복구다.
그래서 JPA 엔티티보다 `schema.sql`과 JDBC 기반 repository를 선택했다.

도메인 모델이 record 중심인 이유:

- 현재 모델은 풍부한 객체 생명주기보다 DB row snapshot에 가깝다.
- 핵심 행위는 `PaymentValidator`, `PaymentComposer`, `CompensationService`, repository 조건부 UPDATE에 있다.
- 이 프로젝트 목적상 ORM 매핑이나 aggregate 설계보다 동시성 제어를 명확하게 드러내는 편이 낫다.

실제 제품으로 확장한다면 Booking/Payment aggregate에 상태 전이 메서드를 더 모으는 방향이 자연스럽다.
이번 범위에서는 YAGNI를 우선한다.

---

## 쟁점 8. 실행 환경

**선택: `./gradlew bootRun` + Spring Boot Docker Compose**

기본 실행:

```bash
./gradlew bootRun
```

Spring Boot Docker Compose가 `docker-compose.yml`의 MySQL/Redis를 자동 기동한다.
명령어로 직접 인프라를 보고 싶은 경우도 같은 compose 파일을 쓴다.

```bash
docker compose up -d
./gradlew bootRun
```

DB 스키마와 seed는 `schema.sql`이 단일 원천이다.
별도 마이그레이션 도구는 이번 범위에서 쓰지 않는다.

---

## 쟁점 9. 관측과 부하 검증

**선택: Actuator/Micrometer + k6 기본 시나리오 2개**

기본 제공:

- `./scripts/test-consistency.sh`: 동시 요청에서 정확히 10건만 CONFIRMED
- `./scripts/test-idempotency.sh`: 같은 checkoutId 중복 요청에서 Booking/Payment 1건
- `./scripts/test-load.sh`: 피크 부하 (기본 1,000 RPS / 60s, `BASE_URL`로 클라우드 대상 가능). 끝나면 `build/load-report.md`를 만든다. 자세한 사용법은 [LOAD.md](LOAD.md).
- `./scripts/test-all.sh`: 단위/통합 테스트 + k6 두 시나리오

메트릭:

- HTTP timing/status는 Actuator 기본 메트릭을 사용한다.
- 커스텀 카운터는 Redis gate, DB stock reserve, booking confirmed, payment failure, 503, compensation failure 정도로 제한한다.
- `userId`, `checkoutId`, `productId` 같은 high-cardinality 값은 태그로 쓰지 않는다.

하지 않는 것:

- k6 spike / Redis 장애 자동 시나리오
- 실제 PG 응답시간 계측
- 운영자용 잔여 재고 API

이 항목들은 선택 확장이다.

선택 확장 — Prometheus + Grafana:

- `/actuator/prometheus`로 Micrometer 메트릭을 노출한다.
- `docker-compose.observability.yml` + `observability/` 디렉토리에 Prometheus / Grafana 설정과 사전 provisioning 대시보드를 포함했다.
- 기본 데모 경로(`./gradlew bootRun` + `./scripts/test-load.sh`)에는 추가 부담을 주지 않는다. 사용법은 [LOAD.md](LOAD.md)의 "실시간 시각화" 단락.
- 