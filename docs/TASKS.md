# TASKS

이 문서는 이 프로젝트를 구현하기 위한 작업 지시서다.
목적은 각 작업 단위를 작고 검증 가능하게 나누어, 사람 또는 AI 에이전트가 순서대로 구현해도 설계 의도가 흐트러지지 않게 하는 것이다.

기준 문서:

- 설계 결정: `docs/DECISIONS.md`
- 도메인 모델: `docs/DOMAIN.md`

## 작업 원칙

- 각 작업 단위는 하나의 기능적 목표를 끝낸다.
- 작업 단위마다 테스트 또는 재현 가능한 검증 방법을 남긴다.
- 인프라, 도메인, 실패 처리, 부하 검증을 한 번에 섞지 않는다.
- 구현 중 설계가 애매하면 먼저 `docs/DECISIONS.md`와 `docs/DOMAIN.md`를 확인한다.
- 요구사항 검증에 직접 필요하지 않은 기능은 만들지 않는다.
- 미구현 기능을 README나 주석에서 구현된 것처럼 쓰지 않는다.

## 공통 구현 지시

- Java 21, Spring Boot 4, Gradle Kotlin DSL을 기본으로 한다.
- 로컬 개발 인프라는 `docker-compose.yml`로 정의한다.
- IntelliJ / Gradle 실행 경로와 `./gradlew test` 모두에서 Spring Boot Docker Compose 지원(
  `testAndDevelopmentOnly("org.springframework.boot:spring-boot-docker-compose")`)으로 MySQL / Redis를 자동 기동한다.
- CLI로 인프라를 직접 확인하고 싶은 경우 `docker compose up -d` 후 `./gradlew bootRun`으로 실행할 수 있게 한다.
- DB 스키마와 초기 데이터는 `schema.sql`로 관리한다.
- MySQL은 최종 영속 저장소, Redis는 게이트와 멱등성 조기 차단 용도로 사용한다.
- Redis gate 통과는 예약 성공이 아니라 DB 재고 선점 시도권 획득이다.
- 사용자 식별은 `X-User-Id` 헤더를 신뢰한다.
- 실제 PG SDK는 붙이지 않는다. `PgClient` 인터페이스와 Fake 구현으로 대체한다.
- 잔여 재고 수량은 사용자 API 응답에 노출하지 않는다.
- 결제는 DB 재고 선점이 성공한 뒤에만 호출한다.
- DB 트랜잭션을 잡은 채 외부 PG 호출을 기다리지 않는다.
- Redis 장애 시 DB로 우회하지 않고 fail-fast한다.
- 같은 `checkoutId`로 들어온 POST Booking은 같은 결과를 반환해야 한다.

## 추천 작업 순서

| 단위 | 제목 | 핵심 목표 |
|---|---|---|
| 1 | Project Bootstrap and Runtime | 코드 수정 없이 실행 가능한 기본 골격 생성 |
| 2 | Schema and Seed Data | 도메인 모델을 DB 스키마와 seed 데이터로 구현 |
| 3 | Checkout API | 주문서 발급과 포인트 조회 구현 |
| 4 | Inventory Gate | Redis 게이트와 DB 재고 선점 구현 |
| 5 | Payment Domain | 복합 결제, 실패 분류, 보상 도메인 구현 |
| 6 | Booking Orchestration | POST Booking 전체 유스케이스 연결 |
| 7 | Failure Recovery and Resilience | 결과 불명, Redis 장애, 정리 잡 처리 |
| 8 | Observability and Load Verification | 정합성, 멱등성, 장애 정책 검증 |
| 9 | Documentation and Final Verification | 실행, 설계, 검증 방법 문서화 |

## Task 1. Project Bootstrap and Runtime

목표:

- Spring Boot 4 기반 프로젝트를 생성한다.
- 앱 실행 시 MySQL, Redis가 자동으로 함께 뜨는 로컬 개발 환경을 만든다.
- 같은 `docker-compose.yml`로 수동 인프라 실행도 가능하게 한다.
- 아직 비즈니스 기능은 넣지 않는다.

입력 문서:

- `docs/DECISIONS.md` 쟁점 8
- `docs/DOMAIN.md` 패키지 초안

작업:

- [x] Gradle Kotlin DSL 프로젝트 생성
- [x] Java 21 설정
- [x] Spring Web, Validation, JDBC, Actuator, MySQL, Redis 의존성 추가
- [x] `testAndDevelopmentOnly("org.springframework.boot:spring-boot-docker-compose")` 추가
- [x] 기본 패키지 구조 생성
- [x] 로컬 개발용 `docker-compose.yml` 작성: MySQL, Redis
- [x] `/actuator/health` 확인 가능하게 구성
- [x] 기본 `application.yml` 작성

완료 조건:

- [x] `./gradlew test` 통과
- [x] `./gradlew bootRun`으로 앱, MySQL, Redis가 함께 기동
- [x] `docker compose up -d` 후 `./gradlew bootRun`으로도 실행 가능
- [x] `/actuator/health` 성공

설계 불변식:

- 비즈니스 로직을 이 작업에 섞지 않는다.
- 실행 가능성의 기반만 만든다.

## Task 2. Schema and Seed Data

목표:

- `docs/DOMAIN.md`의 최소 ERD를 실제 스키마로 내린다.
- 이후 기능 작업이 동일한 DB 모델을 공유하게 한다.

입력 문서:

- `docs/DOMAIN.md` 최소 ERD
- `docs/DOMAIN.md` 최소 테이블 목록
- `docs/DECISIONS.md` 쟁점 7

작업:

- [x] `schema.sql` 추가
- [x] `product`, `stock`, `checkout`, `booking`, `payment`, `payment_component`, `point_account`, `point_ledger` 테이블 생성
- [x] unique 제약 추가: `booking.checkout_id`, `payment.checkout_id`
- [x] 포인트 멱등 제약 추가: `point_ledger(checkout_id, reason)`
- [ ] 핵심 외래키 제약 추가: `stock.product_id`, `checkout.product_id`, `booking.checkout_id`, `booking.product_id`, `payment.checkout_id`, `payment.booking_id`, `payment_component.payment_id`, `point_ledger.user_id`, `point_ledger.checkout_id`
- [x] seed 데이터 추가: 한정 상품 1개, stock 10개, 테스트 사용자 포인트

완료 조건:

- [x] `schema.sql` 기반 스키마 초기화 테스트 통과
- [x] seed 데이터 로딩 테스트 통과
- [ ] ERD 또는 DDL을 README 작성 시 재사용할 수 있는 형태로 정리

설계 불변식:

- 재고의 최종 진실은 DB `stock.qty`다.
- Redis는 이 작업에서 스키마의 일부가 아니다.

## Task 3. Checkout API

목표:

- `GET /checkout`에서 주문서를 발급한다.
- 상품 정보와 사용 가능 포인트 조회를 예약/결제 흐름과 분리한다.

입력 문서:

- `docs/DOMAIN.md` Product
- `docs/DOMAIN.md` Checkout
- `docs/DOMAIN.md` GET Checkout
- `docs/DECISIONS.md` 0.9 #3, #8, #10

작업:

- [x] Product 조회 구현
- [x] PointAccount 조회 구현
- [x] Checkout 생성 구현
- [x] `GET /checkout?productId=...` API 구현
- [x] `X-User-Id` 헤더 검증
- [x] 응답에 `checkoutId`, 상품명, 가격, 대표 이미지, 입/퇴실 시간, 사용 가능 포인트, `expiresAt` 포함
- [x] 잔여 재고는 사용자 응답에 포함하지 않음

완료 조건:

- [x] 정상 checkout 발급 테스트 통과
- [x] 사용자 헤더 누락 테스트 통과
- [x] 같은 사용자가 여러 번 호출하면 서로 다른 checkoutId가 발급되는지 테스트
- [x] 잔여 재고 미노출 테스트 통과

설계 불변식:

- GET Checkout은 의도적으로 비멱등이다.
- POST Booking의 멱등 단위가 될 checkoutId를 서버가 발급한다.
- 이 단계에서 재고를 선점하지 않는다.

## Task 4. Inventory Gate

목표:

- Redis 게이트와 DB 조건부 UPDATE 재고 선점을 구현한다.
- 대량 요청 대부분을 DB 전에 거절할 수 있게 한다.

입력 문서:

- `docs/DECISIONS.md` 쟁점 1
- `docs/DECISIONS.md` 쟁점 2
- `docs/DOMAIN.md` Stock
- `docs/DOMAIN.md` Redis 키

작업:

- [x] `StockGate` 인터페이스 정의
- [x] Redis Lua 기반 `stock:{productId}` 게이트 구현
- [x] `hold:{checkoutId}` 생성 정책 구현
- [x] Redis gate 실패 응답 모델 정의: `sold_out_or_processing`, `retryable`, `retryAfterSeconds`
- [x] DB 조건부 UPDATE 재고 선점 구현
- [x] DB stock 복구 구현
- [x] Redis gate 복구 구현
- [x] checkoutId 기준 보상 상태를 확인해 DB stock과 Redis gate 복구가 한 번만 실행되도록 구현
- [x] Redis 초기화 커맨드 또는 seed 초기화 로직 추가

완료 조건:

- [x] Redis Lua 원자 차감 테스트 통과
- [x] Redis gate 10개 통과 후 11번째 실패 테스트 통과
- [x] DB 조건부 UPDATE가 qty 0 이하로 내려가지 않는지 테스트
- [ ] DB 선점 실패 시 Redis gate 복구 테스트 통과
- [x] 같은 checkoutId로 stock / Redis gate 복구를 반복 호출해도 재고가 중복 증가하지 않는지 테스트
- [ ] 동시성 테스트에서 성공 예약 가능 수가 10개를 넘지 않음

설계 불변식:

- Redis는 게이트, DB는 진실이다.
- Redis gate 통과는 예약 성공이 아니다. 최종 예약 가능 여부는 DB 조건부 UPDATE 성공 여부로 판단한다.
- Redis gate를 통과하지 못한 요청은 DB 재고 선점으로 진행하지 않는다.
- 결제는 DB 재고 선점 성공 이후에만 호출되어야 한다.

## Task 5. Payment Domain

목표:

- 결제 수단별 전략, 복합 결제 조합 검증, 보상 정책을 Booking 로직에서 분리한다.

입력 문서:

- `docs/DECISIONS.md` 쟁점 4
- `docs/DECISIONS.md` 쟁점 6
- `docs/DOMAIN.md` Payment
- `docs/DOMAIN.md` PaymentComponent
- `docs/DOMAIN.md` PointAccount / PointLedger

작업:

- [x] `PaymentMethod` 인터페이스 정의
- [x] `PointPayment`, `CardPayment`, `YPayPayment` 구현
- [x] Fake PG client 구현
- [x] `PaymentValidator` 구현: 카드 + Y페이 금지, 합계 검증
- [x] `PaymentComposer` 구현: 순차 실행, 역순 보상
- [x] PointAccount 차감 구현
- [x] PointLedger 기록과 멱등 제약 처리
- [x] Payment / PaymentComponent 상태 전이 구현
- [x] PG 멱등키로 checkoutId 전달
- [x] PG 결과 조회 인터페이스 추가

완료 조건:

- [x] 카드 단독 성공 테스트 통과
- [x] 포인트 단독 성공 테스트 통과
- [x] 포인트 + 카드 성공 테스트 통과
- [x] 카드 + Y페이 금지 테스트 통과
- [x] 포인트 차감 후 카드 실패 시 포인트 복구 테스트 통과
- [x] PG 타임아웃 또는 응답 미수신 시 `RESULT_PENDING` 테스트 통과
- [x] 같은 checkoutId 보상 재실행이 중복 복구를 만들지 않는지 테스트

설계 불변식:

- 신규 결제 수단 추가 시 BookingService 수정이 최소화되어야 한다.
- PG 실패 확정과 결과 불명 상태를 구분한다.
- 결과 불명 상태에서는 즉시 보상하지 않고 PG 멱등키로 결과 조회를 먼저 시도한다.

## Task 6. Booking Orchestration

목표:

- `POST /bookings` 흐름을 완성한다.
- 재고 선점, 결제 실행, 예약 확정, 멱등 응답을 하나의 유스케이스로 묶는다.

입력 문서:

- `docs/DOMAIN.md` POST Booking
- `docs/DECISIONS.md` 쟁점 1
- `docs/DECISIONS.md` 쟁점 3
- `docs/DECISIONS.md` 쟁점 6

작업:

- [x] `POST /bookings` API 구현
- [x] Redis `idempotency:{checkoutId}` SETNX 처리
- [x] Checkout 사용자 / 만료 / 상태 검증
- [x] 재고 게이트 진입 전 검증 실패 시 `idempotency:{checkoutId}` 삭제
- [x] Redis gate 통과 처리
- [x] DB 트랜잭션에서 재고 선점, Booking `PENDING_PAYMENT`, Payment `PROCESSING` 생성
- [x] 트랜잭션 커밋 후 결제 실행
- [x] 결제 성공 시 Booking `CONFIRMED`, Checkout `USED` 처리
- [x] 확정 실패 시 DB stock 복구, Redis gate 복구, Booking `FAILED` 처리
- [x] 멱등 결과 캐시와 재요청 응답 재생 구현
- [x] DB 재고 선점 이후 실패는 실패 결과 또는 보상 상태를 남겨 같은 checkoutId 재요청이 같은 결과를 보게 함

완료 조건:

- [x] 정상 Booking 성공 테스트 통과
- [x] 같은 checkoutId 동시 요청 시 결제 1회만 수행되는지 테스트
- [x] checkoutId 사용자 불일치 거절 테스트 통과
- [x] checkout 만료 거절 테스트 통과
- [x] Redis gate 실패 시 정확한 잔여 재고 없이 실패 응답 테스트 통과
- [x] 결제 실패 후 재고와 Redis gate 복구 테스트 통과

설계 불변식:

- "결제 성공 후 재고 없음" 흐름이 구조적으로 불가능해야 한다.
- DB 트랜잭션 안에서 외부 PG를 호출하지 않는다.
- 사용자에게 정확한 잔여 재고를 노출하지 않는다.

## Task 7. Failure Recovery and Resilience

목표:

- 정상 흐름 밖의 애매한 상태를 안전하게 수습한다.
- Redis 장애와 PG 결과 불명 상태를 정직하게 처리한다.

입력 문서:

- `docs/DECISIONS.md` 쟁점 5
- `docs/DECISIONS.md` 쟁점 6
- `docs/DOMAIN.md` Payment 상태
- `docs/DOMAIN.md` Redis 키

작업:

- [ ] PG 결과 조회 재시도 잡 구현
- [ ] `RESULT_PENDING` 상태의 hold TTL 연장 구현
- [ ] Checkout / Booking 만료 정리 잡 구현
- [ ] 정리 잡에서 Booking / Payment 상태 확인 후 멱등 복구
- [ ] Redis 장애 시 fail-fast 구현: 짧은 timeout, circuit breaker, `503 Retry-After`
- [ ] 보상 실패 시 `REFUND_FAILED` 상태와 알림 로그 / 메트릭 처리
- [ ] 같은 checkoutId로 보상 재실행 가능하게 구현
- [ ] 보상 단계를 `point_refunded`, `db_stock_restored`, `redis_gate_restored`로 분리해 실패한 단계만 재시도 가능하게 구현

완료 조건:

- [ ] PG 응답 미수신 후 결과 조회 성공 테스트 통과
- [ ] PG 응답 미수신 후 결과 조회 실패 테스트 통과
- [ ] 만료 정리 잡이 성공 결제를 건드리지 않는지 테스트
- [ ] Redis 장애 시 DB 우회 차감이 발생하지 않는지 테스트
- [ ] 보상 실패 후 재실행 테스트 통과
- [ ] DB stock 복구 성공 후 Redis gate 복구 실패 상황에서 재실행 시 DB stock이 중복 증가하지 않는지 테스트

설계 불변식:

- 결과 불명 상태에서는 즉시 환불하지 않고 PG 멱등키로 조회한다.
- Redis 장애 시 fallback은 DB 우회가 아니라 fail-fast다.
- 정리 잡은 `SUCCEEDED` 상태를 변경하면 안 된다.

## Task 8. Observability and Load Verification

목표:

- 정합성, 멱등성, 장애 정책을 재현 가능한 방식으로 검증한다.
- 성능과 동시성 주장을 자동화된 시나리오로 확인할 수 있게 한다.

입력 문서:

- `docs/DECISIONS.md` 쟁점 9
- `docs/DECISIONS.md` 0.2 트래픽 / 부하 프로파일

작업:

- [ ] Actuator / Micrometer 메트릭 노출
- [ ] 핵심 메트릭 추가: Redis gate 성공 / 실패, DB 재고 선점 성공 / 실패, Booking 성공 수, 결제 실패 유형, 503 비율
- [ ] k6 정합성 시나리오 작성
- [ ] k6 멱등성 시나리오 작성
- [ ] 선택 시나리오 작성: 스파이크, Redis 장애
- [ ] 부하 테스트용 compose 또는 실행 스크립트 추가
- [ ] 부하 테스트 실행 명령 문서화

완료 조건:

- [ ] 다수 동시 요청에서 Booking `CONFIRMED`가 정확히 10건인지 확인
- [ ] 같은 checkoutId 동시 요청에서 결제 1건만 발생하는지 확인
- [ ] Redis 장애 시 `503`이 발생하고 DB 우회가 없는지 확인
- [ ] 테스트 결과를 README에 요약할 수 있는 형태로 저장

설계 불변식:

- "정확히 10건 성공"을 자동 검증한다.
- 부하 테스트가 구현되지 않은 기능을 전제로 삼으면 안 된다.

## Task 9. Documentation and Final Verification

목표:

- README만으로 실행, 설계, 검증 방법을 이해할 수 있게 한다.
- 실행, 설계, 검증 방법이 실제 구현과 일치하는지 정리한다.

입력 문서:

- `docs/DECISIONS.md`
- `docs/DOMAIN.md`
- `docs/TASKS.md`

작업:

- [ ] README 작성
- [ ] 기본 실행 방법: `./gradlew bootRun`
- [ ] 수동 인프라 실행 방법: `docker compose up -d` 후 `./gradlew bootRun`
- [ ] API 목록과 curl 예시 추가
- [ ] 아키텍처 요약 추가
- [ ] POST Booking 시퀀스 다이어그램 추가
- [ ] ERD 또는 DDL 포함
- [ ] Redis 장애 정책과 결제 실패 정책 요약
- [ ] k6 실행 방법과 기대 결과 추가
- [ ] `docs/DECISIONS.md`, `docs/DOMAIN.md` 링크 추가

완료 조건:

- [ ] 새 clone 기준 README만 보고 실행 가능
- [ ] README의 API 예시가 실제로 동작
- [ ] README에 ERD 또는 DDL이 직접 포함되어 있음
- [ ] README에 적은 실행 명령과 검증 명령이 실제 파일과 일치함

설계 불변식:

- 미구현 기능을 구현한 것처럼 약속하지 않는다.
- README는 실행 방법, 설계 요약, 검증 방법을 모두 포함한다.

## 구현 순서

1. Task 1-3으로 실행 가능한 읽기 흐름을 먼저 만든다.
2. Task 4에서 재고 정합성의 핵심을 독립적으로 증명한다.
3. Task 5에서 결제 도메인을 Booking 밖에서 완성한다.
4. Task 6에서 전체 POST Booking 흐름을 연결한다.
5. Task 7에서 실패와 복구를 닫는다.
6. Task 8-9에서 검증과 문서화 경험을 완성한다.

## 요구사항 추적성

| 관심사 | 관련 작업 |
|---|---|
| 실행 가능성 | Task 1, Task 9 |
| ERD / DDL | Task 2, Task 9 |
| GET Checkout | Task 3 |
| 재고 정합성 | Task 4, Task 6, Task 8 |
| 선착순 공정성 | Task 4, Task 8 |
| 멱등성 | Task 5, Task 6, Task 8 |
| 복합 결제 확장성 | Task 5 |
| 결제 실패 보상 | Task 5, Task 6, Task 7 |
| Redis 장애 대응 | Task 7, Task 8 |
| 부하 검증 | Task 8 |

## 하지 않을 작업

- 회원가입 / 로그인 / 권한 시스템
- 실제 PG SDK 연동
- 쿠폰, 장바구니, 정산, 매출 리포트
- 대기열 UI, SSE, polling
- 잔여 재고 사용자 노출
- Redis Cluster 기본 구성
- 관리자 API

## 최종 체크리스트

- [ ] `./gradlew test` 통과
- [ ] `./gradlew bootRun`으로 로컬 실행 가능
- [ ] `docker compose up -d` 후 `./gradlew bootRun`으로도 로컬 실행 가능
- [ ] `GET /checkout` 예시 동작
- [ ] `POST /bookings` 성공 예시 동작
- [ ] 동시 요청에서 성공 예약이 정확히 10건
- [ ] 같은 checkoutId 중복 요청에서 결제 1건만 발생
- [ ] 결제 실패 시 DB stock과 Redis gate 복구
- [ ] 결제 실패 보상 재실행 시 DB stock과 Redis gate가 중복 증가하지 않음
- [ ] Redis 장애 시 DB 우회 없이 `503`
- [ ] 최종 점검 전 `docs/TASKS.md` 체크박스가 실제 구현 상태와 일치
- [ ] README에 ERD 또는 DDL 포함
