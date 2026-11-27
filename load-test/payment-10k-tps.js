import http from 'k6/http';
import { check } from 'k6';

const gatewayUrl = __ENV.GATEWAY_URL || 'http://localhost:8080';
const merchantPoolSize = Number(__ENV.MERCHANT_POOL_SIZE || 20000);

export const options = {
  scenarios: {
    payment_10k_tps: {
      executor: 'constant-arrival-rate',
      rate: 10000,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: 3000,
      maxVUs: 15000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250'],
  },
};

export default function () {
  const merchantId = `load-${__VU % merchantPoolSize}`;
  const idempotencyKey = `k6-${__VU}-${__ITER}`;
  const response = http.post(
    `${gatewayUrl}/api/v1/payments/card`,
    JSON.stringify({
      merchantId,
      amount: '12.50',
      currency: 'USD',
      cardToken: 'ct_0123456789abcdef0123456789abcdef',
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      tags: { endpoint: 'card-payment' },
    },
  );

  check(response, {
    'gateway accepts payment': (result) => result.status === 202,
  });
}
