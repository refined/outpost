// Demo dataset: ~100 merchants, ~100k payments over a year, ~8% refunds (30% of them partial), 3 currencies.
//
//   docker compose --profile tools run --rm k6 run seed.js
//   docker compose --profile tools run --rm -e PAYMENTS=10000 k6 run seed.js     # smaller
//
// Deterministic (SEED) and idempotent: rerunning with the same SEED/PREFIX replays the same events (HTTP 200).
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { BASE_URL, JSON_HEADERS, merchants, payment } from './lib.js';

const SEED = Number(__ENV.SEED || 42);
const MERCHANTS = Number(__ENV.MERCHANTS || 100);
const PAYMENTS = Number(__ENV.PAYMENTS || 100000);
const DAYS = Number(__ENV.DAYS || 365);
const PREFIX = __ENV.PREFIX || `seed${SEED}`;
const NOW = Date.parse(__ENV.NOW || new Date().toISOString());

const MERCHANT_LIST = merchants(SEED, MERCHANTS);

export const options = {
  scenarios: {
    seed: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 24), iterations: PAYMENTS, maxDuration: '60m' },
  },
  thresholds: { checks: ['rate>0.999'] },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const { payment: p, refund } = payment(SEED, i, MERCHANT_LIST, DAYS, NOW, PREFIX);

  const captured = http.post(`${BASE_URL}/v1/payments`, JSON.stringify(p), { ...JSON_HEADERS, tags: { name: 'capture' } });
  check(captured, { 'capture 201/200': (r) => r.status === 201 || r.status === 200 });

  if (refund) {
    const refunded = http.post(`${BASE_URL}/v1/payments/${p.paymentId}/refunds`, JSON.stringify(refund), {
      ...JSON_HEADERS,
      tags: { name: 'refund' },
    });
    check(refunded, { 'refund 201/200': (r) => r.status === 201 || r.status === 200 });
  }
}
