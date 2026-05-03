# Claude Harness

이 디렉터리는 Claude Code가 이 프로젝트를 일관되게 작업하도록 돕는 로컬 하네스다.

## 구성

- `../CLAUDE.md`: 항상 따라야 할 프로젝트 가드레일
- `skills/task-executor/SKILL.md`: `docs/TASKS.md`의 Task를 구현하는 절차
- `skills/design-sync/SKILL.md`: 문서와 코드의 설계 정합성을 확인하는 절차
- `skills/final-check/SKILL.md`: 최종 실행 가능성 점검 절차
- `commands/task.md`: 특정 Task를 시작할 때 쓰는 프롬프트
- `commands/design-sync.md`: 문서 동기화 점검 프롬프트
- `commands/final-check.md`: 최종 점검 프롬프트

## 사용 방식

Claude에게 작업을 줄 때는 Task 번호를 명시한다.

예:

```text
docs/TASKS.md의 Task 1을 구현해줘. task-executor 스킬을 따라가.
```

문서 정합성만 확인하려면:

```text
design-sync 기준으로 docs와 구현 계획의 충돌을 점검해줘.
```

최종 점검에는:

```text
final-check 기준으로 실행 가능성과 문서 정합성을 점검해줘.
```
