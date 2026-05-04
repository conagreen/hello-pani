# AI 활용 기록

이 문서는 이 프로젝트를 만들면서 AI 도구를 어떻게 썼고, 어떤 의견을 받아 어디서 채택/거절했는지를 기록한다.
[DECISIONS.md](DECISIONS.md)가 *결과로서의 결정*이라면, 이 문서는 *결정에 도달한 과정*에 가깝다.

## 사용한 도구

| 도구 | 용도 |
|---|---|
| **Claude Code (Anthropic)** | 페어 프로그래밍 메인. 코드 작성·수정, 테스트 실행, 문서 초안. `.claude/` 디렉터리에 작업 가드레일(`CLAUDE.md`)과 절차(`SKILL.md`)가 같이 커밋되어 있다. |
| **Codex (GPT-5)** | Claude의 1차 결론에 대한 *2차 의견*. 큰 설계 분기점에서만 호출했다. |

## 작업 방식

- AI 출력을 그대로 받지 않는다. 모든 결정은 사람이 마지막에 했다.
- 두 도구를 의도적으로 다르게 썼다 — Claude는 *구현 파트너*, Codex는 *반대편의 시선*.
- Claude의 답이 매끄러우면 의심하고 Codex에 다시 물었다. 두 도구가 합의하면 한 번 더 따져보고 가져갔다.
- 코드와 문서 모두 사람이 한 줄씩 읽고 받아들이거나 거절했다. AI 출력을 컨텍스트 없이 그대로 commit하지 않았다.

## 큰 분기점에서의 AI 활용

### 1. Checkout 영속화 모델 — Redis 임시 매핑 + booking 시점 DB INSERT

가장 결정적이었던 분기점이다.

- **초기 구현 (Claude 주도, 사람 승인)**: GET /checkout 시점에 `INSERT INTO checkout` + Redis 캐시 동기화. *"GET이라도 주문서 발급은 영속이어야 한다"*는 직관에 따라 잡았다.
- **사람이 발견한 문제**: 부하 테스트 (`./scripts/test-load.sh`)에서 MySQL INSERT가 GET RPS만큼 따라 올라가는 패턴을 보고, *"거절될 운명의 트래픽이 모두 DB INSERT 한 줄을 만들고 떠난다"*는 모순을 지적.
- **Claude의 1차 답**: "그건 Checkout이 가져야 할 책임(주문서 발급/스냅샷/멱등키/수명관리) 4가지 때문에 어쩔 수 없다."
- **사람의 재반박**: "요구사항은 GET /checkout을 *조회 API*로 정의한다. 영속화가 정말 GET 시점에 필요한가?"
- **Codex에 2차 질의**: 같은 맥락을 던져 *"checkoutId → userId 매핑만 Redis에 두고, DB INSERT는 booking 시점으로 미루는" Redis snapshot 방식*을 추천받음.
- **최종 채택**: Codex의 방향을 사람이 검토 후 채택. Claude가 구현. ([DECISIONS.md 쟁점 3 § Checkout 영속화](DECISIONS.md#쟁점-3-멱등성))
- **검증**: 단일 GET 후 DB row=0 / Redis key=1 확인. rush 시나리오(300 RPS / 10s) 4099 booking 시도 → 정확히 10건 INSERT만 발생.

이 사이클이 *"AI가 답을 주지 않으면 다른 AI에게 물어보고, 둘 다 사람이 최종 검토"* 의 전형이다.

### 2. 결제 확장성 (`PaymentMethod` + `PaymentComposer` + `PaymentValidator`)

- 처음에는 `BookingService` 안에 if/switch로 결제 수단 분기. *"새 수단 추가 시 Booking 수정이 커진다"*는 요구사항을 위반.
- Claude에 "결제 수단을 닫힌 sealed/record로 정리하고, charge/refund 전략과 검증을 분리하자"고 지시.
- Claude가 `PaymentMethod`(strategy), `PaymentComposer`(orchestration), `PaymentValidator`(조합/금액 검증) 3분리를 제안. 채택.
- 사람 수정: Validator가 *PG 호출 전에 거절*하도록 위치 강제 (테스트로 보호).

### 3. 보상 멱등성 — `compensation_step` UNIQUE

- Claude의 첫 제안: "보상 함수가 항상 같은 결과를 만들도록 짜면 된다."
- 사람이 거절: "보상이 부분 실패하고 재시도되면? `point_refunded`만 끝나고 `db_stock_restored`에서 죽으면 다음 호출이 또 포인트를 환불할 수 있다."
- Claude가 단계별 unique step 테이블로 수정. 채택.
- 결과적으로 요구사항의 *"보상은 단계별 멱등 재시도"* 항목에 가장 강하게 응답하는 부분이 됐다.

### 4. Redis 장애 정책 — fail-fast vs DB 우회

- Claude의 첫 제안에 "Redis 죽으면 DB로 우회 차감"이 들어 있었다.
- 사람이 즉시 거절: "그건 Redis 게이트의 존재 의미를 무너뜨린다. 대량 트래픽이 DB로 직격하면 정합성보다 가용성을 깰 가능성이 더 크다."
- Resilience4j circuit breaker + 200ms timeout + `503 + Retry-After`로 합의. ([DECISIONS.md 쟁점 5](DECISIONS.md#쟁점-5-redis-장애))
- `RedisFailFastTest`를 추가해 *"DB 우회 코드를 누가 실수로 넣지 못하게"* 자동 강제.

### 5. 부하 시각화 — Prometheus + Grafana 대시보드

- Claude에 *"23개 패널 / 5개 행"* 짜리 대시보드 초안을 받음. 5초 새로고침으로 부하 진행이 한눈에.
- 사람 수정 1: MySQL 패널이 쿼리 종류 구분이 안 돼 답답하다 → Claude에 "verb 단위로 쪼개라" 지시 → `mysql_global_status_commands_total{command=~"select|insert|update|delete"}` PromQL.
- 사람 수정 2: Spring Boot 4에서 `tomcat_threads_busy_threads` 메트릭이 사라짐 → Claude가 CPU 사용률 패널로 대체 제안.
- mysqld-exporter perf_schema 권한이 없다는 *"Permission denied"* 로그를 보고 Claude에 init script(`grant-exporter.sql`) 작성 지시.

## AI 출력을 거절한 사례

- "Redis 죽으면 DB로 우회" (위 4번)
- "GET /checkout이 INSERT하는 건 어쩔 수 없다" (위 1번)
- 보상 함수의 멱등성을 함수 자체로만 보장하자는 첫 제안 (위 3번)
- 큰 단위 한꺼번에 리팩터링하자는 제안 → 항상 작은 단위로 쪼개서 검증

## 정리

AI는 *손이 빠른 페어*였지, 결정자가 아니었다.
Claude의 답이 깔끔할수록 한 번 더 의심했고, 핵심 분기점에서는 Codex의 시선을 일부러 끌어와 부딪혔다.
요구사항이 *"왜 그 선택을 했는가"* 를 본질로 본다는 점에서, 이 문서가 [DECISIONS.md](DECISIONS.md)의 짝이다.
