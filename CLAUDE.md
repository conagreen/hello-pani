# CLAUDE

이 파일은 Claude에게 이 저장소의 작업 방식을 지시하는 하네스다.
작업을 시작할 때는 먼저 이 파일과 `docs/TASKS.md`를 읽고, 요청된 Task의 범위를 벗어나지 않는다.

## 기준 문서

- 작업 지시서: `docs/TASKS.md`
- 설계 결정: `docs/DECISIONS.md`
- 도메인 모델: `docs/DOMAIN.md`

## 기본 태도

- 사용자의 최신 지시를 최우선으로 따른다.
- 구현 전 현재 파일 상태를 확인한다.
- 작업 범위 밖 리팩터링을 하지 않는다.
- 미구현 기능을 README, 주석, 문서에서 구현된 것처럼 쓰지 않는다.
- 애매한 설계는 임의로 확장하지 말고 `docs/DECISIONS.md`와 `docs/DOMAIN.md`를 기준으로 해석한다.
- 요구사항 검증에 직접 필요하지 않은 기능은 만들지 않는다.

## 구현 불변식

- Java 21, Spring Boot 4, Gradle Kotlin DSL을 기본으로 한다.
- DB 스키마와 초기 데이터는 `schema.sql`로 관리한다.
- 별도 DB 마이그레이션 도구를 도입하지 않는다.
- MySQL은 최종 영속 저장소다.
- Redis는 재고 게이트와 멱등성 조기 차단에 사용한다.
- Redis gate 통과는 예약 성공이 아니라 DB 재고 선점 시도권 획득이다.
- Redis 장애 시 DB로 우회하지 않고 fail-fast한다.
- 잔여 재고 수량은 사용자 API 응답에 노출하지 않는다.
- 결제는 DB 재고 선점이 성공한 뒤에만 호출한다.
- DB 트랜잭션을 잡은 채 외부 PG 호출을 기다리지 않는다.
- 실제 PG SDK는 붙이지 않는다. `PgClient` 인터페이스와 Fake 구현만 사용한다.
- 같은 `checkoutId`의 POST Booking 요청은 같은 결과를 반환해야 한다.
- 결제 실패 보상은 checkoutId 기준으로 멱등해야 하며, `point_refunded`, `db_stock_restored`, `redis_gate_restored` 단계 중 실패한 단계만 재시도한다.

## 로컬 실행 원칙

- `docker-compose.yml`은 MySQL / Redis 정의의 단일 원천이다.
- IntelliJ / Gradle 실행 경로는 Spring Boot Docker Compose를 사용한다.
- Gradle 의존성에는 다음 개발 전용 의존성을 포함한다.

```kotlin
developmentOnly("org.springframework.boot:spring-boot-docker-compose")
```

- 기본 실행 경로는 `./gradlew bootRun`이다.
- CLI로 인프라를 직접 확인할 때는 `docker compose up -d` 후 `./gradlew bootRun`을 사용한다.

## 작업 흐름

1. `docs/TASKS.md`에서 요청된 Task 번호와 범위를 확인한다.
2. 해당 Task의 `입력 문서`, `작업`, `완료 조건`, `설계 불변식`을 요약한다.
3. 현재 코드와 문서를 읽어 이미 완료된 부분을 확인한다.
4. 필요한 파일만 수정한다.
5. Task의 완료 조건에 맞는 테스트 또는 검증 명령을 실행한다.
6. 실패한 검증이 있으면 원인을 수정하거나, 실행하지 못한 이유를 명확히 남긴다.
7. 작업 후 관련 문서가 틀어졌는지 확인한다.
8. 사용자가 작업 결과를 승인하거나 OK 사인을 주면, 실제 구현과 검증이 끝난 항목만 `docs/TASKS.md` 체크박스에 반영한다.
9. 최종 응답에는 변경 파일, 검증 결과, 남은 리스크를 짧게 쓴다.

## 검증 명령 실행 원칙

- 결정론적으로 끝나는 검증 명령은 직접 실행한다. 예: `./gradlew test`, 특정 Gradle 테스트, `rg` 기반 정합성 확인, `git status --short`.
- 실패가 나면 한 번 이상 원인을 수정하고 같은 명령을 재실행한다.
- 장시간 떠 있는 서버 명령은 목적이 명확할 때만 실행한다. 예: `./gradlew bootRun`.
- `bootRun`을 실행한 경우 health check 등 필요한 확인을 마치면 프로세스를 종료한다.
- 네트워크 다운로드, destructive command, git history 변경은 사용자 승인이 필요하다.
- 검증을 실행하지 못한 경우 "왜 못 했는지"와 "대신 무엇을 확인했는지"를 최종 응답에 남긴다.

## 스킬 사용 지침

- Task 구현을 요청받으면 `.claude/skills/task-executor/SKILL.md`를 따른다.
- 문서와 코드의 정합성 확인을 요청받으면 `.claude/skills/design-sync/SKILL.md`를 따른다.
- 최종 점검을 요청받으면 `.claude/skills/final-check/SKILL.md`를 따른다.

## 금지 / 주의

- Redis Cluster를 기본 구성으로 만들지 않는다.
- 회원가입, 로그인, 쿠폰, 장바구니, 정산, 관리자 API, 실제 PG 연동을 만들지 않는다.
- 테스트 통과를 위해 설계 불변식을 약화하지 않는다.
