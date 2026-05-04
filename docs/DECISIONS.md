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
- **사용자 클라이언트의 시계와 네트워크 지연은 신뢰하지 않는다** — 모든 순서/시간 판단(checkoutId 발급 시각, 만료, 멱등 cache TTL, 보상 단계 기록)은 서버 시계 기준이다.

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

### 현실 세계로 확장 — 팀/서비스 분리 시의 한계

본 설계는 *상품 / 주문 / 결제가 같은 DB를 공유하는 단일 서비스*를 가정한다.
`BookingService.reserveInTransaction`이 stock UPDATE + booking INSERT + payment INSERT를 한 트랜잭션에 묶을 수 있는 이유다.

실제 운영에서 상품 팀과 결제 팀이 *서로 다른 DB / 마이크로서비스로 분리*되면 이 단일 트랜잭션 가정이 깨진다. 그 경우의 일반적 대응:

- **Outbox 패턴**: 결제 서비스 호출 의도를 자기 DB에 같은 트랜잭션으로 기록하고, 별도 워커가 그 의도를 picking 해서 결제 서비스에 호출. 호출 결과는 다시 outbox로 받아 상태 전이.
- **Saga**: 각 서비스가 자기 단계의 보상 액션을 책임지고, orchestrator 또는 choreography로 단계별 진행/롤백.

본 설계도 *외부 PG 호출은 이미 트랜잭션 밖*에서 하므로 ([쟁점 6](#쟁점-6-결제-실패와-보상)), 결제 서비스 분리는 *현재의 보상 흐름을 그대로 확장*하면 된다 — `compensation_step (checkout_id, step) UNIQUE` 멱등이 saga의 step 멱등과 같은 패턴이고, `pgIdempotencyKey`가 saga의 correlation id 역할을 한다.

본 범위에서는 모놀리스 가정으로 단순화했지만, *결제 호출의 보상 단계 멱등 처리*가 saga 확장의 직접 토대다.

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
- **클라이언트가 보내는 시각 / 순서 정보를 신뢰**하는 정책 — 사용자 시계는 조작 가능하고, 네트워크 지연은 통제 불가능하다. 클라이언트에서 받은 timestamp나 sequence number는 *공정성 판단에 사용하지 않는다*. 도착 순서는 오직 *서버의 Redis Lua가 본 순서*만이 진실이다.

복구된 재고는 별도 알림이나 예약 없이, 이후 다시 Redis gate에 도달한 요청이 획득한다.

---

## 쟁점 3. 멱등성

**선택: 서버가 발급한 checkoutId를 POST Booking 멱등키로 사용**

### 왜 GET /checkout이 비멱등이어도 괜찮은가 — 상용 서비스 관찰

GET이 비멱등이라 불편해 보일 수 있다. 그러나 이 API의 *위치*를 보면 자연스럽다.
사용자는 *이미 상품을 골라 주문 의도를 만든 뒤* 결제 직전 화면에 도착한다 — URL은 `hello-pani.example.com/checkout?주문스냅샷키=...` 같은 *주문서 진입* 화면이다.

상용 호텔 / 여행 예약 서비스의 결제 직전 화면 흐름을 *사용자 시점에서* 관찰한 결과, 다수가 같은 패턴을 보인다 (백엔드 구현은 SSR/REST/GraphQL 등 다양해 단언하지 않는다 — 관찰된 *사용자-facing pattern*만 정리한다):

1. 사용자가 상품 → 옵션 선택 → "예약하기" 버튼으로 checkout 화면에 도착
2. URL에 *주문서 스냅샷 키* (UUID 형태)가 노출되고, 화면은 그 스냅샷에 묶인 가격 / 사용 가능 포인트 / 만료시각을 보여준다 (이전 화면에서 supplier 정보 등이 URL 파라미터로 함께 전달되는 케이스도 있음 — 예: `?orderSnapshotKey=...&supplierToken=...`)
3. 페이로드에 *서버 시각*(예: `serverDateTime`)이 함께 포함돼 클라이언트가 만료를 표시하면서도 *판단은 서버 시계 기준*임을 분명히 한다
4. 사용자는 이 스냅샷을 기준으로 결제 수단을 고르고 결제 버튼을 누름
5. 결제 동작이 POST 류 호출 — 스냅샷 키를 멱등키로 사용

이 패턴의 핵심은 *"가격/만료 등 결제 기준이 되는 값을 화면 진입 시점에 동결해 UUID로 키잉한다"* 이다. 새로고침이나 URL 공유에도 안전하고, 결제 동작에만 멱등성을 적용하면 된다.

본 프로젝트는 이 관찰된 패턴 중 *멱등성과 정합성에 직접 관계된 부분*을 따른다:

- GET /checkout이 호출될 때마다 새 checkoutId를 발급 — *최신 가격/포인트 잔액*을 매번 다시 보여줄 수 있다
- 만료는 *서버 측 TTL*로만 결정 ([쟁점 11 / 핵심 불변식](#핵심-불변식): 클라이언트 시계 신뢰하지 않음)
- POST 동작에만 *checkoutId 멱등키*를 적용

본 프로젝트가 *따르지 않은* 부분 (단순화):

- SSR로 페이로드 inline hydration → 우리는 일반 REST JSON 응답
- 이전 화면에서 inventory 토큰을 URL로 carry → 우리는 productId만 받고 매번 stock 조회
- 다단계 약관/공급사 상세 등 — 우리는 한정 1상품 도메인이라 단일 단계로 충분

확장 시: SSR / 여러 단계 카트 / 공급사 분리 환경으로 가면 *결제 멱등키 (checkoutId)*는 그대로 유지되고, 그 *주변 메타데이터의 carry 방식*만 바뀐다.

### 정책

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
- 이 프로젝트의 목적상 ORM 매핑이나 aggregate 설계보다 동시성 제어를 명확하게 드러내는 편이 낫다.

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

---

## 쟁점 10. 분산 환경에서의 멀티 인스턴스 안전성

요구사항은 "2대 이상의 애플리케이션 서버로 구성된 분산 환경"을 가정한다.
이 프로젝트는 인스턴스 수에 무관하게 같은 결과를 내도록 설계됐다.
이 단락은 그 이유를 단계별로 정리한다.

### 인스턴스 로컬 상태 = 0

- 큐, in-memory cache, sticky session 같은 인스턴스 로컬 상태를 두지 않는다.
- 모든 정합성 결정은 외부 저장소(Redis, MySQL)에서 한다.
- 그래서 같은 사용자가 다른 인스턴스로 라우팅되어도 결과가 같다.

### 게이트와 멱등성 — Redis 단일 인스턴스의 직렬화

- `stock:{productId}` 차감과 `hold:{checkoutId}` 생성은 단일 Redis Lua script로 원자 실행된다.
- Lua가 단일 Redis 노드 내에서 직렬화되므로, 서로 다른 앱 인스턴스에서 동시에 들어와도 *Redis가 본 도착 순서*가 유일한 진실이 된다.
- `SETNX idempotency:{checkoutId}`도 같은 원리로 멱등 진입권을 정확히 1회만 부여한다.

### 최종 재고 — DB 조건부 UPDATE

- `UPDATE stock SET qty = qty - 1 WHERE product_id = ? AND qty > 0`은 RDB 자체의 row-level lock으로 직렬화된다.
- `affectedRows == 0`이면 *내가 진 거*다. 인스턴스 수, 동시성 정도와 무관하게 정확히 10건만 통과한다.
- DB unique 제약(`booking.checkout_id`, `payment.checkout_id`)이 *드물지만 발생할 수 있는* 다중 통과 시도를 같은 트랜잭션 내에서 거절한다.

### Scheduled job 동시 실행 — 멱등 transition

`@Scheduled` 잡 두 개(`ExpiryCleanupJob`, `PaymentResolutionJob`)는 인스턴스마다 동시에 돈다.
잡에 단일 leader election(예: Quartz Cluster, ShedLock)을 *의도적으로 도입하지 않았다*.
대신 **같은 row에 같은 transition을 두 번 적용해도 안전하도록** 구조를 잡았다.

- `PgClient.lookupResult(pgIdempotencyKey)`: PG 결과는 idempotency key 기준으로 결정적이다. 두 인스턴스가 같은 RESULT_PENDING payment를 동시에 조회해도 같은 결과를 받는다.
- 상태 전이: PROCESSING → SUCCEEDED, RESULT_PENDING → FAILED 같은 transition은 *최종 상태가 같으면* 두 번 실행해도 결과가 같다.
- 보상 단계: `compensation_step (checkout_id, step) UNIQUE` 제약으로 같은 단계가 두 번 실행되지 않는다. `point_ledger (checkout_id, reason) UNIQUE`도 같은 역할을 한다.
- 트랜잭션 충돌: 두 인스턴스가 동시에 같은 row를 UPDATE하면 RDB가 row lock을 직렬화한다. 늦게 들어온 트랜잭션은 같은 최종 상태를 한 번 더 쓸 뿐이다.

비용은 *드물게 발생하는 중복 작업*뿐이다. 정확성을 위해 받아들인다.

### 인프라 측면

- DB 스키마는 `schema.sql`이 단일 원천이고 `CREATE TABLE IF NOT EXISTS` + `INSERT ... ON DUPLICATE KEY UPDATE`라 여러 인스턴스가 동시에 부팅해도 충돌하지 않는다.
- 시드 INSERT는 `product_id = product_id` 패턴으로 멱등이라 다중 부팅에서 안전.
- `application.yml`의 모든 설정은 인스턴스 식별자와 무관하다.

### 그러므로

이 시스템에 인스턴스를 N대 띄우는 것은 *부하 분산* 외의 효과가 없다.
정합성은 Redis Lua + DB 조건부 UPDATE + DB unique + 멱등 transition이라는 4중 게이트가 책임진다.
한 대를 띄우든 열 대를 띄우든 *정확히 10건만 CONFIRMED, 나머지는 모두 빠르게 거절* 이 동일하다.

### 한계 — 명시

- Redis는 단일 인스턴스다. Redis 자체가 SPOF다. 운영 환경에서는 Sentinel/Cluster를 붙여야 하지만 본 범위에서는 가용성 옵션으로 분리해 둔다.
- 잡 leader election을 도입하지 않은 만큼, 잡이 무거워지면 N대가 같은 일을 N번 한다. 현재 잡 부하는 `findIssuedExpiredBefore` / `findAllByStatus(RESULT_PENDING)` 정도라 비용이 작지만, 부하가 커지면 ShedLock 도입을 고려한다.

### Walk-the-talk — 분산 환경 데모

위 주장을 *문서가 아니라 실행 결과로* 증명하기 위해 `docker-compose.scale.yml`을 같이 둔다.

```bash
./scripts/test-distributed.sh
```

이 한 줄이 다음을 수행한다.

1. `./gradlew bootBuildImage`로 OCI 이미지(`hello-pani:0.0.1-SNAPSHOT`) 1회 생성 (캐시).
2. `app-1`, `app-2` 두 컨테이너를 같은 `mysql` / `redis`를 공유하는 형태로 띄운다.
3. `nginx` 컨테이너가 둘 사이를 round-robin 한다 (`X-Upstream` 응답 헤더로 라우팅 인스턴스 노출).
4. `k6/consistency.js`를 nginx 뒤로 보내 50 VU 동시 진입.
5. 종료 후 DB에서 `booking.status='CONFIRMED'` 카운트와 `stock.qty`를 확인하고, 두 컨테이너 로그에 booking 처리 흔적이 모두 있는지를 같이 본다.

기대 결과: **`CONFIRMED == 10`, `stock.qty == 0`, 두 인스턴스 모두에서 POST /bookings 처리 발생** (Actuator metric으로 인스턴스별 카운트 비교).
nginx는 host:18080에 노출된다 (bootRun 8080 / 다른 dev 도구 8081과 충돌 회피).

이 데모가 통과한다는 것은:

- 같은 사용자가 GET을 app-1에서 받고 POST를 app-2로 보내도 (또는 그 반대로) 결과가 같다 — *진짜 stateless*.
- 두 번째 인스턴스를 띄우는 데 별도 leader election / 마이그레이션 / 설정 동기화가 없다 — *수평 확장에 열려있음*.
- N=2가 통과하면 N=10 / N=100도 같은 이유로 통과한다.

---

## 쟁점 11. 잔여 재고 비노출

**선택: GET /checkout과 POST /bookings 어디에서도 정확한 잔여 재고 수를 응답에 담지 않는다**

요구사항이 잔여 재고 노출을 명시적으로 금지하지는 않는다.
하지만 다음 이유로 *능동적으로 숨기는* 정책을 택했다.

### 왜 숨기는가

- **공정성과의 충돌.** 잔여 재고가 보이면 사용자는 *0이 되기 직전*에 미리 폼을 채우고 대기하다가 일제히 누른다. 결과적으로 "선착순"이 "예측 게임"이 된다 ([쟁점 2](#쟁점-2-공정성)와 같은 정신).
- **고가용성과의 충돌.** 정확한 잔여 재고는 *Redis gate counter나 DB stock의 실시간 값*이다. 노출하면 캐싱이 어렵고, 사용자마다 매번 정확한 값을 만들어 줘야 해서 거절 경로의 가벼움이 깨진다.
- **잘못된 신호.** Redis gate를 통과한 사용자도 DB 선점에서 실패할 수 있다. 즉 "잔여 1개"라는 표시는 *조건부 가능성*이지 *내가 살 수 있다*는 약속이 아니다. 사용자에게 보여주면 클레임 비용만 늘어난다.

### 무엇을 보여주는가

- POST 결과만: `CONFIRMED` / `SOLD_OUT_OR_PROCESSING` / `FAILED` / `PENDING`.
- GET /checkout은 *상품 정보, 가격, 사용 가능 포인트, checkoutId, 만료시각*만 응답한다 — 잔여 수량은 빠져 있다.
- 운영자 관점의 잔여 재고는 Grafana의 *MySQL stock 쿼리 패널*과 Actuator 메트릭(`booking.confirmed`)으로 확인한다.

### 트레이드오프

- 사용자 UX는 단순해진다 — 누르고 결과를 받는다.
- 사용자가 "왜 안 됐는지"의 디버깅 단서를 잃는다. 그 대가로 정합성과 공정성을 얻는다.
- "이미 종료됐습니다"를 게이트 거절 응답(`409 SOLD_OUT_OR_PROCESSING`)으로 갈음한다. 정확하지는 않지만 거절 경로 1건당 비용이 일정하다.

### 만약 노출한다면

추후 노출이 필요하다면 *5초 단위로 Redis cache에서 읽은 근사값*을 헤더로 내보내는 정도로 한정해야 한다. 결코 실시간 정확값을 응답 본문에 담지 않는다.
