# Task Command

사용자가 특정 Task 구현을 요청하면 이 프롬프트를 따른다.

```text
docs/TASKS.md에서 요청된 Task를 찾고, .claude/skills/task-executor/SKILL.md 절차를 따라 구현해줘.

반드시 지켜야 할 것:
- Task 범위를 넘는 기능을 만들지 않는다.
- docs/DECISIONS.md와 docs/DOMAIN.md의 설계 불변식을 확인한다.
- schema.sql을 사용한다.
- docker-compose.yml은 MySQL / Redis 정의의 단일 원천으로 둔다.
- Spring Boot Docker Compose와 수동 docker compose 실행 경로를 모두 깨지지 않게 한다.
- 구현 후 완료 조건에 맞는 검증을 실행한다.
- ./gradlew test처럼 결정론적으로 끝나는 검증 명령은 직접 실행한다.
```
