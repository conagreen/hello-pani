// 피크 부하 시나리오 — 한 파일에서 SCENARIO 환경변수로 부하 패턴을 전환한다.
// 보고서는 build/load-report-${SCENARIO}.md로 분리 저장된다.
//
// 시나리오:
//   SCENARIO=rush (기본)
//     "오픈된 직후 즉시 구매" 패턴. 매 iteration이 GET /checkout + POST /bookings.
//     변수: PEAK_RPS (1000), PEAK_DURATION (60s), PEAK_RAMP (15s)
//
//   SCENARIO=browse
//     "오픈 대기 중 새로고침" 패턴. GET /checkout만 도배. POST 없음.
//     변수: PEAK_RPS (300), PEAK_DURATION (60s), PEAK_RAMP (5s)
//
//   SCENARIO=spike
//     "대기 → 풀림 → 폭주" 2-phase. browse phase 후 rush phase가 이어진다.
//     변수: BROWSE_RPS (200), BROWSE_DURATION (30s), PEAK_RPS (1000), PEAK_DURATION (30s),
//           PEAK_RAMP (5s)
//
// 공통 변수: BASE_URL, PRODUCT_ID, PRICE
//
// 한 iteration 의 GET+POST 조합이라 SCENARIO=rush에서 RPS=1000이면 실제 HTTP 트래픽은 ~2000 req/s.

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const PRICE = parseInt(__ENV.PRICE || '150000');

const SCENARIO = (__ENV.SCENARIO || 'rush').toLowerCase();
const PEAK_RPS = parseInt(__ENV.PEAK_RPS || (SCENARIO === 'browse' ? '300' : '1000'));
const PEAK_DURATION = __ENV.PEAK_DURATION || (SCENARIO === 'spike' ? '30s' : '60s');
const PEAK_RAMP = __ENV.PEAK_RAMP || (SCENARIO === 'browse' ? '5s' : '15s');
const BROWSE_RPS = parseInt(__ENV.BROWSE_RPS || '200');
const BROWSE_DURATION = __ENV.BROWSE_DURATION || '30s';

const browseHits = new Counter('browse_hits_total');
const bookingConfirmed = new Counter('booking_confirmed_total');
const bookingSoldOut = new Counter('booking_sold_out_total');
const bookingDuplicate = new Counter('booking_duplicate_total');
const booking503 = new Counter('booking_503_total');
const bookingError = new Counter('booking_error_total');
const bookingLatency = new Trend('booking_latency_ms', true);

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: buildScenarios(),
  thresholds: buildThresholds(),
};

function buildScenarios() {
  if (SCENARIO === 'browse') {
    return {
      browse: {
        executor: 'ramping-arrival-rate',
        startRate: 50,
        timeUnit: '1s',
        preAllocatedVUs: 100,
        maxVUs: 800,
        exec: 'browseOnly',
        stages: [
          { target: PEAK_RPS, duration: PEAK_RAMP },
          { target: PEAK_RPS, duration: PEAK_DURATION },
          { target: 0, duration: '5s' },
        ],
      },
    };
  }
  if (SCENARIO === 'spike') {
    return {
      browse: {
        executor: 'ramping-arrival-rate',
        startRate: 50,
        timeUnit: '1s',
        preAllocatedVUs: 100,
        maxVUs: 800,
        exec: 'browseOnly',
        stages: [
          { target: BROWSE_RPS, duration: '5s' },
          { target: BROWSE_RPS, duration: BROWSE_DURATION },
        ],
      },
      rush: {
        executor: 'ramping-arrival-rate',
        startRate: 50,
        timeUnit: '1s',
        preAllocatedVUs: 200,
        maxVUs: 2000,
        exec: 'browseAndBuy',
        startTime: addSeconds('5s', BROWSE_DURATION),
        stages: [
          { target: PEAK_RPS, duration: PEAK_RAMP },
          { target: PEAK_RPS, duration: PEAK_DURATION },
          { target: 0, duration: '5s' },
        ],
      },
    };
  }
  // default: rush
  return {
    rush: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      exec: 'browseAndBuy',
      stages: [
        { target: PEAK_RPS, duration: PEAK_RAMP },
        { target: PEAK_RPS, duration: PEAK_DURATION },
        { target: 0, duration: '5s' },
      ],
    },
  };
}

function buildThresholds() {
  // browse는 GET만 하므로 booking 카운터 검증 안 함.
  if (SCENARIO === 'browse') {
    return {
      browse_hits_total: ['count>0'],
      'http_req_failed{kind:checkout}': ['rate<0.01'],
    };
  }
  return {
    booking_confirmed_total: ['count==10'],
    booking_error_total: ['count==0'],
    'http_req_failed{kind:checkout}': ['rate<0.01'],
  };
}

function addSeconds(a, b) {
  return `${parseSeconds(a) + parseSeconds(b)}s`;
}

function parseSeconds(d) {
  if (!d) return 0;
  const m = String(d).match(/^(\d+)\s*(s|m|h)?$/);
  if (!m) return 0;
  const n = parseInt(m[1], 10);
  const u = m[2] || 's';
  return u === 'h' ? n * 3600 : u === 'm' ? n * 60 : n;
}

export function browseOnly() {
  const userId = `browse-${__VU}-${__ITER}`;
  const headers = { 'X-User-Id': userId };

  const issue = http.get(`${BASE}/checkout?productId=${PRODUCT_ID}`, {
    headers,
    tags: { kind: 'checkout' },
  });
  if (issue.status === 200) {
    browseHits.add(1);
  } else {
    bookingError.add(1, { stage: 'checkout', http: String(issue.status) });
  }
}

export function browseAndBuy() {
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
    productId: parseInt(PRODUCT_ID),
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
  const reportPath = `build/load-report-${SCENARIO}.md`;
  return {
    'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    [reportPath]: md,
  };
}

function formatReport(data) {
  const total = pickCount(data, 'http_reqs');
  const confirmed = pickCount(data, 'booking_confirmed_total');
  const soldOut = pickCount(data, 'booking_sold_out_total');
  const duplicate = pickCount(data, 'booking_duplicate_total');
  const errors = pickCount(data, 'booking_error_total');
  const five03 = pickCount(data, 'booking_503_total');
  const browse = pickCount(data, 'browse_hits_total');
  const bookingReqs = confirmed + soldOut + duplicate + five03 + errors;

  const latency = data.metrics.booking_latency_ms;
  const latencyValues = latency ? latency.values : {};
  const p50 = fmt(latencyValues.med);
  const p95 = fmt(latencyValues['p(95)']);
  const p99 = fmt(latencyValues['p(99)']);
  const max = fmt(latencyValues.max);

  const thresholdTable = Object.entries(data.metrics)
      .filter(([_, m]) => m.thresholds)
      .map(([name, m]) =>
          Object.entries(m.thresholds)
              .map(([rule, th]) => `| \`${name}\` | \`${rule}\` | ${th.ok ? '통과' : '실패'} |`)
              .join('\n'),
      )
      .filter(Boolean)
      .join('\n');

  const lines = [];
  lines.push(`# 피크 부하 보고서 — ${SCENARIO}`);
  lines.push('');
  lines.push(`생성 시각: ${new Date().toISOString()}`);
  lines.push('');
  lines.push('## 부하 패턴');
  lines.push('');
  lines.push(scenarioDescription());
  lines.push('');
  lines.push('## 환경');
  lines.push('');
  lines.push(`- 대상: \`${BASE}\``);
  if (SCENARIO === 'spike') {
    lines.push(`- browse phase: ${BROWSE_RPS} RPS / ${BROWSE_DURATION}`);
    lines.push(`- rush phase: ${PEAK_RPS} RPS / ${PEAK_DURATION} (ramp ${PEAK_RAMP})`);
  } else {
    lines.push(`- 목표 RPS: ${PEAK_RPS}`);
    lines.push(`- 유지 시간: ${PEAK_DURATION}`);
    lines.push(`- ramp-up: ${PEAK_RAMP}`);
  }
  lines.push('');
  lines.push('## 결과');
  lines.push('');
  lines.push('| 항목 | 값 |');
  lines.push('|---|---|');
  lines.push(`| 총 HTTP 요청 | ${total} |`);
  if (SCENARIO === 'browse') {
    lines.push(`| GET /checkout 200 | ${browse} |`);
  } else {
    lines.push(`| POST /bookings 요청 | ${bookingReqs} |`);
    lines.push(`| CONFIRMED | ${confirmed} |`);
    lines.push(`| 409 SOLD_OUT_OR_PROCESSING | ${soldOut} |`);
    lines.push(`| 409 DUPLICATE_REQUEST_PROCESSING | ${duplicate} |`);
    lines.push(`| 503 REDIS_UNAVAILABLE | ${five03} |`);
    if (SCENARIO === 'spike') {
      lines.push(`| GET /checkout (browse phase) | ${browse} |`);
    }
    lines.push(`| 에러 (예상 외) | ${errors} |`);
  }
  lines.push('');

  if (SCENARIO !== 'browse') {
    lines.push('## POST /bookings latency');
    lines.push('');
    lines.push('| 지표 | ms |');
    lines.push('|---|---|');
    lines.push(`| p50 | ${p50} |`);
    lines.push(`| p95 | ${p95} |`);
    lines.push(`| p99 | ${p99} |`);
    lines.push(`| max | ${max} |`);
    lines.push('');
  }

  lines.push('## 통과 기준');
  lines.push('');
  lines.push('| 메트릭 | 규칙 | 결과 |');
  lines.push('|---|---|---|');
  lines.push(thresholdTable || '| - | - | - |');
  lines.push('');
  lines.push('## 결론');
  lines.push('');
  lines.push(conclusion(SCENARIO, confirmed, errors, browse));
  lines.push('');
  return lines.join('\n');
}

function scenarioDescription() {
  if (SCENARIO === 'browse') {
    return '오픈 대기 중 새로고침 패턴. POST /bookings 없이 GET /checkout만 도배한다. 평시 부하의 근사치이며, 시스템이 검색/주문서 발급으로 어느 정도 부하를 받는지를 본다.';
  }
  if (SCENARIO === 'spike') {
    return '대기(browse) → 풀림(rush) 2-phase. 사용자가 오픈 시각까지 페이지를 새로고침하다가, 오픈 직후 곧장 구매를 시도하는 시나리오. 대시보드에서 GET /checkout 라인이 먼저 살아있다가 rush 시점에 POST /bookings 라인이 폭발하는 모습으로 구분된다.';
  }
  return '오픈된 직후 즉시 구매. 매 iteration이 GET /checkout + POST /bookings를 연속으로 친다. 단일 phase rush.';
}

function conclusion(scenario, confirmed, errors, browse) {
  if (scenario === 'browse') {
    if (errors === 0 && browse > 0) {
      return `✅ ${browse}건의 GET /checkout 처리, 에러 0. 평시 부하 패턴에서 시스템 정상.`;
    }
    return `❌ browse 시나리오에서 에러 발생. 위 카운터 분포를 확인하라.`;
  }
  if (confirmed === 10 && errors === 0) {
    return '✅ 정확히 10건 CONFIRMED, 에러 0. 핵심 불변식 통과.';
  }
  return '❌ 핵심 불변식 위반. 위 카운터 분포를 확인하라.';
}

function pickCount(data, name) {
  const m = data.metrics[name];
  if (!m) return 0;
  return m.values && m.values.count ? m.values.count : 0;
}

function fmt(n) {
  if (n === undefined || n === null) return '-';
  return Math.round(n * 100) / 100;
}
