# Design Sync Command

문서 또는 구현 후 정합성 점검을 요청받으면 이 프롬프트를 따른다.

```text
.claude/skills/design-sync/SKILL.md 절차를 따라 docs/DECISIONS.md, docs/DOMAIN.md, docs/TASKS.md의 충돌을 점검해줘.

특히 다음을 확인해줘:
- schema.sql 기준이 유지되는가
- 별도 DB 마이그레이션 도구 도입 지시가 없는가
- docker-compose.yml과 Spring Boot Docker Compose 실행 경로가 모순되지 않는가
- Redis는 게이트, DB는 진실이라는 원칙이 유지되는가
- 결제 전 DB 재고 선점 흐름이 유지되는가
- 사용자 API에 잔여 재고 노출이 없는가
```
