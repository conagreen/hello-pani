# 부하 테스트 1-pager

`./scripts/test-load.sh` 한 줄로 피크 부하를 돌리고 `build/load-report-${SCENARIO}.md`를 만든다.

## TL;DR

```bash
./scripts/test-load.sh                 # 기본: rush 시나리오
SCENARIO=browse ./scripts/test-load.sh # 평시 / 오픈 대기 패턴
SCENARIO=spike  ./scripts/test-load.sh # 대기 → 풀림 → 폭주 2-phase
# 끝나면 build/load-report-${SCENARIO}.md 열어서 본다.
```

이 시스템은 거절 경로(Redis Lua → 5–10ms)가 본업이라, 노트북에서도 1,000 RPS 도달이 가능하다.

## 시나리오

| 이름 | 패턴 | 기본값 | 보고서 파일 |
|---|---|---|---|
| `rush` (기본) | 오픈 직후 즉시 구매. 매 iteration GET + POST | 1,000 RPS / 60s | `build/load-report-rush.md` |
| `browse` | 오픈 대기 새로고침. GET /checkout만 도배 | 300 RPS / 60s | `build/load-report-browse.md` |
| `spike` | browse → rush 2-phase | 200/30s → 1,000/30s | `build/load-report-spike.md` |

대시보드 "엔드포인트별 트래픽" 패널을 보면 시나리오가 즉시 구분된다.

- `rush`: GET / POST 둘 다 동시에 치솟음
- `browse`: GET 라인만 활성, POST는 0
- `spike`: GET 라인이 먼저 살아있다가 일정 시점에 POST 라인이 폭발

### MySQL 패널에서 보이는 부하 패턴 (DECISIONS 쟁점 3 참조)

GET /checkout은 DB에 INSERT하지 않고 Redis cache에 매핑만 둔다. DB INSERT는 게이트 통과자(10명)만 booking 시점에 일괄로 수행되므로:

| 시나리오 | MySQL INSERT rate | 의미 |
|---|---|---|
| `browse` (GET only, 300 RPS) | **0 INSERT/s** (idle) | 거절 경로가 진짜 0 DB hit. Redis cache.put만 발생 |
| `rush` (1,000 RPS) | 짧은 spike (~40 INSERT 한 번에 발생, 그 후 0) | 게이트 통과한 10명만 booking 시점에 INSERT 4×10 = 40 row |
| `spike` (browse → rush) | browse phase 0, rush phase에 짧은 spike | phase 전환이 INSERT 곡선으로 명시 |

이 비대칭이 *"Redis는 게이트 + 임시 캡처, DB는 최종 진실"* 책임 분리의 시각적 증거다. 이전 모델(GET /checkout이 DB INSERT) 대비 거절 경로 DB 부하가 100% 제거됨.

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

## 실시간 시각화 (선택 — Prometheus + Grafana)

부하 진행을 차트로 보고 싶으면 별도 compose 파일을 함께 띄운다. 기본 데모 경로엔 영향 없다.

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
./gradlew bootRun                                 # 호스트에서 앱 실행
./scripts/test-load.sh                            # 다른 셸에서 부하
open http://localhost:3000                        # admin/admin
```

`hello-pani — 한정 재고 선착순 시스템` 대시보드가 자동 provisioning 된다. 5개 Row, 23개 패널:

- **결과 요약** — 현재 시간 범위 내 CONFIRMED / 보상 실패 / 503 / DB 선점 (`increase($__range)` 기반이라 부하별로 자동 리셋)
- **Booking 트래픽** — 엔드포인트별 트래픽(GET vs POST, 시나리오 식별), Redis gate rate, /bookings 응답 코드별 rate, latency p50/p95/p99, 결제 실패 분류
- **앱 부하** — JVM heap, GC pause, CPU 사용률, Hikari pool, Resilience4j Redis 회로 상태
- **MySQL 부하** — 쿼리 처리량 (select/insert/update + 시스템에서 발생하는 SQL 종류 description), 연결 수, slow query 누계, InnoDB 디스크 I/O, SQL row 처리량(rows_examined / rows_sent)
- **Redis 부하** — 명령 처리량, keyspace hits/misses, 메모리, 연결 클라이언트 수

대시보드 새로고침은 5초 간격이라 부하 진행 동안 거절 99.99% / 통과 10건이 한눈에 보이고, 동시에 인프라 부하가 어떻게 움직이는지(=거절 경로 본업이라 MySQL은 대부분 idle, Redis만 분주)가 같이 드러난다.

> **MySQL exporter 권한 노트**: perf_schema digest 통계를 읽으려면 `hellopani` 계정에 PROCESS / REPLICATION CLIENT / performance_schema SELECT 권한이 필요하다. `observability/mysql-init/grant-exporter.sql`이 mysql 컨테이너 첫 기동 시 자동 적용되며, 이미 데이터 볼륨이 있는 상태로 observability를 추가하는 경우 한 번 수동 실행이 필요하다:
>
> ```bash
> docker exec -i hello-pani-mysql-1 mysql -u root -proot < observability/mysql-init/grant-exporter.sql
> ```

정리:

```bash
./scripts/docker-clean.sh                       # 컨테이너만 정지 (볼륨 유지)
./scripts/docker-clean.sh --observability-only  # Prometheus/Grafana/exporter만
./scripts/docker-clean.sh --all                 # 다 내림
./scripts/docker-clean.sh --volumes             # 데이터까지 완전 삭제
```

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

### JVM warmup으로 인한 초기 latency 튐

ramp-up 구간(처음 ~15초)의 p95/p99는 JVM JIT 컴파일과 클래스 로딩이 끝나기 전이라 평소보다 크게 튈 수 있다. 첫 부하 직전에 다음 중 하나를 하면 꼬리 분포가 안정된다.

- 부하 시작 전 30초간 낮은 RPS로 핸드 워밍 (`PEAK_RPS=100 PEAK_DURATION=30s ./scripts/test-load.sh` 한 번 돌리고 본 시나리오)
- `actuator/health`를 수십 회 미리 친 뒤 시작
- ramp-up을 길게 (`PEAK_RAMP=60s`) 잡아 점진적으로 진입

보고서의 latency p95/p99가 평소보다 높게 나오면 GC / JIT 영향을 의심하라. CONFIRMED 카운트는 영향받지 않는다.

이 시스템의 본업이 "거절을 빠르게"이므로, 보고서의 핵심은 **CONFIRMED == 10** 한 줄이다. 나머지는 부수 정보다.
