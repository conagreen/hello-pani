import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const PRICE = parseInt(__ENV.PRICE || '150000');

const bookingConfirmed = new Counter('booking_confirmed_total');
const bookingSoldOut = new Counter('booking_sold_out_total');
const bookingFailed = new Counter('booking_failed_total');
const bookingError = new Counter('booking_error_total');

export const options = {
  scenarios: {
    rush: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 50,
      maxDuration: '60s',
    },
  },
  thresholds: {
    booking_confirmed_total: ['count==10'],
    booking_error_total: ['count==0'],
  },
};

export default function () {
  const userId = `k6-cons-${__VU}-${__ITER}-${Date.now()}`;
  const headers = { 'X-User-Id': userId, 'Content-Type': 'application/json' };

  const issue = http.get(`${BASE}/checkout?productId=${PRODUCT_ID}`, { headers });
  if (issue.status !== 200) {
    bookingError.add(1, { stage: 'checkout', http: String(issue.status) });
    return;
  }
  const checkoutId = issue.json('checkoutId');
  if (!checkoutId) {
    bookingError.add(1, { stage: 'checkout', http: 'no_checkoutId' });
    return;
  }

  const body = JSON.stringify({
    checkoutId,
    payments: [{ method: 'CARD', amount: PRICE }],
  });
  const res = http.post(`${BASE}/bookings`, body, { headers });

  if (res.status === 200) {
    const status = res.json('status');
    if (status === 'CONFIRMED') {
      bookingConfirmed.add(1);
    } else if (status === 'FAILED') {
      bookingFailed.add(1);
    } else {
      bookingError.add(1, { stage: 'booking', http: '200', body_status: String(status) });
    }
  } else if (res.status === 409) {
    bookingSoldOut.add(1);
  } else if (res.status === 503) {
    bookingError.add(1, { stage: 'booking', http: '503' });
  } else {
    bookingError.add(1, { stage: 'booking', http: String(res.status) });
  }
}
