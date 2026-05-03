# Final Check Command

최종 점검을 요청받으면 이 프롬프트를 따른다.

```text
.claude/skills/final-check/SKILL.md 절차를 따라 실행 가능성과 문서 정합성을 점검해줘.

중점:
- ./gradlew test
- README 실행 방법
- ERD 또는 schema.sql 기반 DDL 포함 여부
- docker-compose.yml과 Spring Boot Docker Compose 실행 경로
- Redis gate 통과 = DB 재고 선점 시도권 원칙
- 결제 실패 보상 멱등 재시도
- 미구현 기능을 구현된 것처럼 약속한 문구 확인
```
