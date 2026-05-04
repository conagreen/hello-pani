import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const PRICE = parseInt(__ENV.PRICE || '150000');
const USER_ID = __ENV.USER_ID || 'k6-idem-user';

const bookingConfirmed = new Counter('booking_confirmed_total');
const bookingPending = new Counter('booking_pending_total');
const bookingFailed = new Counter('booking_failed_total');
const duplicateProcessing = new Counter('booking_duplicate_processing_total');
const bookingError = new Counter('booking_error_total');

export const options = {
  scenarios: {
    duplicates: {
      executor: 'shared-iterations',
      vus: 20,
      iterations: 20,
      maxDuration: '30s',
    },
  },
  thresholds: {
    booking_confirmed_total: ['count>=1', 'count<=20'],
    booking_error_total: ['count==0'],
    booking_failed_total: ['count==0'],
  },
};

export function setup() {
  const issue = http.get(`${BASE}/checkout?productId=${PRODUCT_ID}`, {
    headers: { 'X-User-Id': USER_ID },
  });
  if (issue.status !== 200) {
    throw new Error(`checkout issue failed: ${issue.status} ${issue.body}`);
  }
  const checkoutId = issue.json('checkoutId');
  if (!checkoutId) {
    throw new Error('checkout response missing checkoutId');
  }
  return { checkoutId };
}

export default function (data) {
  const headers = { 'X-User-Id': USER_ID, 'Content-Type': 'application/json' };
  const body = JSON.stringify({
    checkoutId: data.checkoutId,
    productId: parseInt(PRODUCT_ID),
    payments: [{ method: 'CARD', amount: PRICE }],
  });
  const res = http.post(`${BASE}/bookings`, body, { headers });

  if (res.status === 200) {
    const status = res.json('status');
    if (status === 'CONFIRMED') {
      bookingConfirmed.add(1);
    } else if (status === 'PENDING') {
      bookingPending.add(1);
    } else if (status === 'FAILED') {
      bookingFailed.add(1);
    } else {
      bookingError.add(1, { stage: 'booking', body_status: String(status) });
    }
  } else if (res.status === 409) {
    duplicateProcessing.add(1);
  } else {
    bookingError.add(1, { stage: 'booking', http: String(res.status) });
  }
}
