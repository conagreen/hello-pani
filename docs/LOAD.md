# 부하 테스트 1-pager

`./scripts/test-load.sh` 한 줄로 피크 부하를 돌리고 `build/load-report.md`를 만든다.

## TL;DR

```bash
./scripts/test-load.sh
# 끝나면 build/load-report.md 열어서 본다.
```

기본값: 1,000 RPS / 60초 / ramp-up 15초.
이 시스템은 거절 경로(Redis Lua → 5–10ms)가 본업이라, 노트북에서도 1,000 RPS 도달이 가능하다.

## 통과 기준

| 메트릭 | 규칙 |
|---|---|
| `booking_confirmed_total` | `count == 10` (정확히 10건만 성공) |
| `booking_error_total` | `count == 0` (예상 외 실패 0) |
| `http_req_failed{kind:checkout}` | `rate < 0.01` (GET /checkout 실패율 1% 미만) |

latency p50/p95/p99는 보고서에 기록되지만 통과 기준은 아니다 — 같은 머신에서 앱+MySQL+Redis+k6를 돌리면 thermal/CPU 경쟁으로 절대 수치가 흔들리기 때문이다.

## 보고서

`build/load-report.md`에는 다음이 들어 있다.

1. 환경 (대상 URL, RPS, duration, ramp)
2. 응답 분포 (CONFIRMED / 409 SOLD_OUT / 409 DUPLICATE / 503 / 에러)
3. POST /bookings latency p50/p95/p99/max
4. k6 thresholds 통과 여부
5. 한 줄 결론

## 시나리오 조정

```bash
# 풀 스펙 (DECISIONS.md 0.2 — 1,000 TPS 5분, 누적 ~300k 요청)
PEAK_DURATION=5m ./scripts/test-load.sh

# 가벼운 스모크
PEAK_RPS=200 PEAK_DURATION=30s ./scripts/test-load.sh

# ramp 길게 잡고 burn
PEAK_RPS=1000 PEAK_RAMP=60s PEAK_DURATION=5m ./scripts/test-load.sh
```

5분 burn은 노트북 발열로 후반 latency가 망가질 수 있다. 데모용은 60초가 적당하다.

## 클라우드 대상 실행

`BASE_URL`이 localhost가 아니면 스크립트가 자동으로 로컬 앱 기동과 DB/Redis 초기화를 스킵한다. 그 경우 운영자가 다음을 책임진다.

- 대상 호스트에 앱 + MySQL + Redis 배포
- 매 부하 테스트 전 stock=10 / 잔여 데이터 정리

```bash
BASE_URL=https://hello-pani.example.com ./scripts/test-load.sh
```

배포 가이드는 이 문서의 범위 밖이지만, 최소 환경변수는 다음 정도면 된다.

```bash
SPRING_DATASOURCE_URL=jdbc:mysql://<rds-host>:3306/hellopani
SPRING_DATASOURCE_USERNAME=hellopani
SPRING_DATASOURCE_PASSWORD=...
SPRING_DATA_REDIS_HOST=<elasticache-host>
SPRING_DATA_REDIS_PORT=6379
```

이미지는 `./gradlew bootBuildImage`로 만든다 (Spring Boot built-in, Buildpack 기반).

## 운영 시 주의

- **Spring Boot Docker Compose는 앱 종료 시 컨테이너를 함께 내릴 수 있다.** 5분 burn처럼 긴 테스트는 `docker compose up -d` + `./gradlew bootRun` 조합으로 인프라/앱 lifecycle을 분리하는 것이 안전하다.
- **macOS의 file descriptor 한도 (기본 256)** 에서 1,000 RPS는 부족할 수 있다. `ulimit -n 8192`를 미리 올린 셸에서 실행한다.
- **Hikari pool / Tomcat thread pool** 기본값이 부족하다고 느껴지면 `application.yml`에 다음을 추가한다 (이번 범위 기본값으로는 1,000 RPS 통과를 확인했다).

```yaml
server:
  tomcat:
    threads:
      max: 400
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
```

## 측정의 한계

같은 머신 동거 측정이라 절대 수치는 참고용이다. 정확한 latency 분포가 필요하면 다음 중 하나를 한다.

- 별도 노드에서 k6 실행 (가장 정확)
- 클라우드 배포 + k6 로컬 실행 (네트워크 지연 포함)
- k6 Cloud / Grafana k6 OSS 분산 실행 (이번 범위 밖)

이 시스템의 본업이 "거절을 빠르게"이므로, 보고서의 핵심은 **CONFIRMED == 10** 한 줄이다. 나머지는 부수 정보다.
