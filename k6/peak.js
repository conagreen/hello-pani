// 피크 부하 시나리오 — DECISIONS.md 0.2의 "1,000 TPS 5분" 스펙을 환경변수로 조정 가능하게 둔다.
// 기본값(1,000 RPS / 60s)은 노트북에서 데모하기 좋은 크기로 잡혀 있다.
//
// 환경변수:
//   BASE_URL        대상 호스트 (기본: http://localhost:8080)
//   PRODUCT_ID      상품 id (기본: 1)
//   PRICE           가격 (기본: 150000)
//   PEAK_RPS        목표 booking 시도 RPS (기본: 1000)
//   PEAK_DURATION   목표 RPS 유지 시간 (기본: 60s, 풀 스펙은 5m)
//   PEAK_RAMP       ramp-up 시간 (기본: 15s)
//
// 한 iteration = GET /checkout + POST /bookings. 즉 PEAK_RPS=1000이면 실제 HTTP 트래픽은 ~2000 req/s.

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const PRICE = parseInt(__ENV.PRICE || '150000');
const PEAK_RPS = parseInt(__ENV.PEAK_RPS || '1000');
const PEAK_DURATION = __ENV.PEAK_DURATION || '60s';
const PEAK_RAMP = __ENV.PEAK_RAMP || '15s';

const bookingConfirmed = new Counter('booking_confirmed_total');
const bookingSoldOut = new Counter('booking_sold_out_total');
const bookingDuplicate = new Counter('booking_duplicate_total');
const booking503 = new Counter('booking_503_total');
const bookingError = new Counter('booking_error_total');
const bookingLatency = new Trend('booking_latency_ms', true);

export const options = {
  discardResponseBodies: false,
  // 보고서에 p50/p95/p99까지 채우기 위해 Trend 통계 항목을 명시한다.
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    peak: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages: [
        { target: PEAK_RPS, duration: PEAK_RAMP },
        { target: PEAK_RPS, duration: PEAK_DURATION },
        { target: 0, duration: '5s' },
      ],
    },
  },
  // 통과 기준 — "정확히 10건만 성공"이 본 시스템의 핵심 불변식이다.
  // latency는 정보용으로만 둔다 (머신 동거 측정의 한계).
  thresholds: {
    booking_confirmed_total: ['count==10'],
    booking_error_total: ['count==0'],
    'http_req_failed{kind:checkout}': ['rate<0.01'],
  },
};

export default function () {
  const userId = `peak-${__VU}-${__ITER}`;
  const headers = { 'X-User-Id': userId, 'Content-Type': 'application/json' };

  const issue = http.get(`${BASE}/checkout?productId=${PRODUCT_ID}`, {
    headers,
    tags: { kind: 'checkout' },
  });
  if (issue.status !== 200) {
    bookingError.add(1, { stage: 'checkout', http: String(issue.status) });
    return;
  }
  const checkoutId = issue.json('checkoutId');
  if (!checkoutId) {
    bookingError.add(1, { stage: 'checkout', reason: 'no_checkoutId' });
    return;
  }

  const body = JSON.stringify({
    checkoutId,
    payments: [{ method: 'CARD', amount: PRICE }],
  });
  const res = http.post(`${BASE}/bookings`, body, {
    headers,
    tags: { kind: 'booking' },
  });

  bookingLatency.add(res.timings.duration);

  if (res.status === 200) {
    let parsedStatus = null;
    try {
      parsedStatus = res.json('status');
    } catch (e) {
      bookingError.add(1, { stage: 'booking', reason: 'parse_error' });
      return;
    }
    if (parsedStatus === 'CONFIRMED') {
      bookingConfirmed.add(1);
    } else if (parsedStatus === 'FAILED') {
      // 정상 시나리오에서는 발생하지 않아야 한다 (CARD 카드만 사용).
      bookingError.add(1, { stage: 'booking', body_status: 'FAILED' });
    } else {
      bookingError.add(1, { stage: 'booking', body_status: String(parsedStatus) });
    }
  } else if (res.status === 409) {
    const code = safeJson(res, 'code');
    if (code === 'DUPLICATE_REQUEST_PROCESSING') {
      bookingDuplicate.add(1);
    } else {
      bookingSoldOut.add(1);
    }
  } else if (res.status === 503) {
    booking503.add(1);
  } else {
    bookingError.add(1, { stage: 'booking', http: String(res.status) });
  }
}

function safeJson(res, path) {
  try {
    return res.json(path);
  } catch (e) {
    return null;
  }
}

export function handleSummary(data) {
  const md = formatReport(data);
  return {
    'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    'build/load-report.md': md,
  };
}

function formatReport(data) {
  const total = pickCount(data, 'http_reqs');
  const confirmed = pickCount(data, 'booking_confirmed_total');
  const soldOut = pickCount(data, 'booking_sold_out_total');
  const duplicate = pickCount(data, 'booking_duplicate_total');
  const errors = pickCount(data, 'booking_error_total');
  const five03 = pickCount(data, 'booking_503_total');
  const bookingReqs = confirmed + soldOut + duplicate + five03 + errors;

  // booking_latency_ms는 직접 정의한 Trend라 sub-metric 자동 생성 이슈가 없다.
  const latency = data.metrics.booking_latency_ms;
  const latencyValues = latency ? latency.values : {};
  const p50 = fmt(latencyValues.med);
  const p95 = fmt(latencyValues['p(95)']);
  const p99 = fmt(latencyValues['p(99)']);
  const max = fmt(latencyValues.max);

  const thresholdTable = Object.entries(data.metrics)
      .filter(([_, m]) => m.thresholds)
      .map(([name, m]) => {
        return Object.entries(m.thresholds)
            .map(([rule, th]) => `| \`${name}\` | \`${rule}\` | ${th.ok ? '통과' : '실패'} |`)
            .join('\n');
      })
      .filter(Boolean)
      .join('\n');

  const passed = confirmed === 10 && errors === 0;
  const conclusion = passed
      ? '✅ 정확히 10건 CONFIRMED, 에러 0. 핵심 불변식 통과.'
      : '❌ 핵심 불변식 위반. 위 카운터 분포를 확인하라.';

  return [
    '# 피크 부하 보고서',
    '',
    `생성 시각: ${new Date().toISOString()}`,
    '',
    '## 환경',
    '',
    `- 대상: \`${BASE}\``,
    `- 목표 RPS: ${PEAK_RPS} (booking iteration 기준)`,
    `- 유지 시간: ${PEAK_DURATION}`,
    `- ramp-up: ${PEAK_RAMP}`,
    '',
    '## 결과',
    '',
    '| 항목 | 값 |',
    '|---|---|',
    `| 총 HTTP 요청 | ${total} |`,
    `| POST /bookings 요청 | ${bookingReqs} |`,
    `| CONFIRMED | ${confirmed} |`,
    `| 409 SOLD_OUT_OR_PROCESSING | ${soldOut} |`,
    `| 409 DUPLICATE_REQUEST_PROCESSING | ${duplicate} |`,
    `| 503 REDIS_UNAVAILABLE | ${five03} |`,
    `| 에러 (예상 외) | ${errors} |`,
    '',
    '## POST /bookings latency',
    '',
    '| 지표 | ms |',
    '|---|---|',
    `| p50 | ${p50} |`,
    `| p95 | ${p95} |`,
    `| p99 | ${p99} |`,
    `| max | ${max} |`,
    '',
    '## 통과 기준',
    '',
    '| 메트릭 | 규칙 | 결과 |',
    '|---|---|---|',
    thresholdTable || '| - | - | - |',
    '',
    '## 결론',
    '',
    conclusion,
    '',
  ].join('\n');
}

function pickCount(data, name) {
  const m = data.metrics[name];
  if (!m) return 0;
  return m.values && m.values.count ? m.values.count : 0;
}

function pickCountWithTag(data, name, tagKey, tagValue) {
  const key = `${name}{${tagKey}:${tagValue}}`;
  const m = data.metrics[key];
  if (!m) return 0;
  return m.values && m.values.count ? m.values.count : 0;
}

function fmt(n) {
  if (n === undefined || n === null) return '-';
  return Math.round(n * 100) / 100;
}
