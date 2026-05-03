# Skill: design-sync

문서와 구현 계획 또는 코드가 서로 충돌하지 않는지 확인할 때 사용한다.

## 입력 문서

- `docs/DECISIONS.md`
- `docs/DOMAIN.md`
- `docs/TASKS.md`
- README가 생긴 뒤에는 `README.md`도 포함

## 점검 절차

1. 세 문서의 역할을 구분한다.
   - `DECISIONS.md`: 왜 그렇게 선택했는가
   - `DOMAIN.md`: 무엇을 만들 것인가
   - `TASKS.md`: 어떤 순서로 구현할 것인가
2. 다음 키워드 충돌을 검색한다.
   - 별도 DB 마이그레이션 도구
   - Docker Compose / docker-compose / bootRun
   - 오래된 재고 차감 표현
   - Redis Cluster / Sentinel
   - 잔여 재고 노출
   - 사용자 취소
   - 실제 PG
   - 대기열 / SSE / polling
3. 다음 설계 불변식을 확인한다.
   - DB 스키마는 `schema.sql` 기준
   - `docker-compose.yml`은 MySQL / Redis 정의의 단일 원천
   - Spring Boot Docker Compose와 수동 `docker compose up -d` 경로를 모두 지원
   - Redis는 게이트, DB는 진실
   - Redis gate 통과는 예약 성공이 아니라 DB 재고 선점 시도권
   - 결제는 DB 재고 선점 뒤에만 호출
   - 외부 PG 호출은 DB 트랜잭션 밖에서 수행
   - PG 결과 불명은 checkoutId / pgIdempotencyKey로 결과 조회
   - 결제 실패 보상은 `point_refunded`, `db_stock_restored`, `redis_gate_restored` 단계별 멱등 재시도
   - 사용자 API는 잔여 재고 수량을 노출하지 않음
4. 문서 간 표현이 다르면 다음 기준으로 정리한다.
   - 결정 근거는 `DECISIONS.md`에 둔다.
   - 도메인 속성과 흐름은 `DOMAIN.md`에 둔다.
   - 구현 순서와 완료 조건은 `TASKS.md`에 둔다.
5. 수정이 필요한 경우 최소 범위로 패치한다.

## 보고 형식

```text
정합성 점검 결과:

문제:
- 파일:라인 - 내용

수정:
- ...

남은 확인:
- ...
```

문제가 없으면 "문서 간 충돌 없음"이라고 명확히 말한다.
