# Skill: final-check

최종 실행 가능성, 문서 요구사항, 구현 약속의 정합성을 점검할 때 사용한다.

## 점검 대상

- 전체 저장소
- `README.md`
- `docs/DECISIONS.md`
- `docs/DOMAIN.md`
- `docs/TASKS.md`

## 절차

1. 작업 트리 상태를 확인한다.
   - `git status --short`
2. 실행 가능성 확인 명령을 실행한다. 결정론적으로 끝나는 명령은 직접 실행한다.
   - `./gradlew test`
   - 필요 시 `./gradlew bootRun`
3. README 요구사항을 확인한다.
   - 기본 실행 방법: `./gradlew bootRun`
   - 수동 인프라 실행 방법: `docker compose up -d` 후 `./gradlew bootRun`
   - API 예시
   - ERD 또는 `schema.sql` 기반 DDL
   - Redis 장애 정책
   - 결제 실패 정책
   - k6 검증 방법
4. 구현 약속과 실제 파일을 대조한다.
   - README에 쓴 실행 명령이 실제로 존재하는가
   - k6 스크립트를 언급했다면 파일이 있는가
   - Grafana 대시보드를 언급했다면 실제 import 파일이 있는가
5. 문서와 구현의 핵심 불변식을 대조한다.
   - `schema.sql` 기준이 유지되는가
   - `docker-compose.yml`과 Spring Boot Docker Compose 실행 경로가 모두 유효한가
   - Redis 장애 시 DB 우회 fallback을 약속하거나 구현하지 않았는가
   - 사용자 API가 잔여 재고 수량을 노출하지 않는가
6. 발견한 문제는 우선순위와 함께 보고한다.

## 실행 원칙

- `./gradlew test`는 반드시 실행한다.
- `rg` 기반 문서 / 코드 정합성 검색은 반드시 실행한다.
- `./gradlew bootRun`은 README 실행 가능성 확인이 필요한 경우 실행하고, 확인 후 종료한다.
- 명령 실패가 있으면 수정 가능한지 판단하고 재시도한다.
- 실행하지 못한 명령은 이유를 보고한다.

## 금지

- 사용자의 명시 승인 없이 git history를 재작성하지 않는다.
- 사용자의 명시 승인 없이 destructive command를 실행하지 않는다.
- 미구현 항목을 README에서 구현된 것처럼 표현하지 않는다.

## 보고 형식

```text
최종 점검 결과:

통과:
- ...

수정 필요:
- [높음] ...
- [중간] ...
- [낮음] ...

실행한 명령:
- ...
```
