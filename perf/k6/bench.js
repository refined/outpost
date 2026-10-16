// One benchmark step: a constant arrival rate for a fixed duration, then a one-line JSON result.
// Driven by perf/run-benchmarks.sh; can also be run alone:
//   docker compose --profile tools run --rm -e SCENARIO=capture -e RATE=500 k6 run bench.js
//
// SCENARIO
//   capture  captures spread over 100 merchants, 3 currencies (the realistic case)
//   hot      every capture to one merchant in one jurisdiction (worst-case contention on shared rows)
//   mixed    85% capture, 10% refund of an earlier payment, 5% balances report
//   report   GET /v1/reports/balances only (read latency; run after a preload)
//
// A second scenario polls /v1/ledger/lag twice a second: how stale a balance read is (BALANCE_MODE=BATCHED) and how
// many entries wait for the next snapshot refresh. Its requests are excluded from the step's numbers.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, JSON_HEADERS, merchants, payment } from './lib.js';

const SCENARIO = __ENV.SCENARIO || 'capture';
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '30s';
const RUN = __ENV.RUN || `b${Date.now().toString(36)}`;
const OUT = __ENV.OUT || `${SCENARIO}-${RATE}`;
const MERCHANTS = merchants(7, 100);
const NOW = Date.now();
const readLag = new Trend('lag_read_ms');
const pending = new Trend('lag_pending_entries');

export const options = {
  discardResponseBodies: true,
  scenarios: {
    step: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(1500, Math.max(20, Math.ceil(RATE / 5))),
      maxVUs: 3000,
    },
    lag: { executor: 'constant-arrival-rate', exec: 'probeLag', rate: 2, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 2 },
  },
  // Thresholds that always pass, only so that the summary carries the per-scenario submetrics.
  thresholds: {
    'http_req_duration{scenario:step}': ['max>=0'],
    'iterations{scenario:step}': ['count>=0'],
    'checks{scenario:step}': ['rate>=0'],
    'dropped_iterations{scenario:step}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const mine = []; // per-VU: payments this VU captured, candidates for refunds in the mixed scenario

function capture(i, hot) {
  const { payment: p } = payment(7, i, MERCHANTS, 30, NOW, `${RUN}-${SCENARIO}`);
  if (hot) Object.assign(p, { merchantId: 'm-hot', currency: 'EUR', taxRate: '0.21', taxJurisdiction: 'NL' });
  const r = http.post(`${BASE_URL}/v1/payments`, JSON.stringify(p), { ...JSON_HEADERS, tags: { name: 'capture' } });
  if (check(r, { 'capture ok': (res) => res.status === 201 || res.status === 200 })) mine.push(p);
}

export function probeLag() {
  const r = http.get(`${BASE_URL}/v1/ledger/lag`, { responseType: 'text', tags: { name: 'lag' } });
  if (r.status !== 200) return;
  const lag = r.json();
  readLag.add(lag.readLagMs);
  pending.add(lag.pendingEntries);
}

export default function () {
  const i = exec.scenario.iterationInTest;
  if (SCENARIO === 'report') {
    const r = http.get(`${BASE_URL}/v1/reports/balances`, { tags: { name: 'report' } });
    check(r, { 'report ok': (res) => res.status === 200 });
    return;
  }
  if (SCENARIO !== 'mixed') return capture(i, SCENARIO === 'hot');

  const roll = (i * 2654435761 >>> 0) % 100;
  if (roll < 85 || mine.length === 0) return capture(i, false);
  if (roll < 95) {
    const p = mine.splice((i >>> 3) % mine.length, 1)[0]; // each payment refunded at most once by this VU
    const body = { refundId: `${p.paymentId}-r`, amount: Math.max(1, Math.floor(p.amount / 2)) };
    const r = http.post(`${BASE_URL}/v1/payments/${p.paymentId}/refunds`, JSON.stringify(body), { ...JSON_HEADERS, tags: { name: 'refund' } });
    check(r, { 'refund ok': (res) => res.status === 201 || res.status === 200 });
    return;
  }
  const r = http.get(`${BASE_URL}/v1/reports/balances`, { tags: { name: 'report' } });
  check(r, { 'report ok': (res) => res.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const sub = (name) => m[`${name}{scenario:step}`];
  const dur = sub('http_req_duration').values;
  const seconds = data.state.testRunDurationMs / 1000;
  const iterations = sub('iterations') ? sub('iterations').values.count : 0;
  const lagStat = (metric, stat) => (m[metric] ? Math.round(m[metric].values[stat]) : 0);
  const row = {
    variant: __ENV.VARIANT || 'local',
    scenario: SCENARIO,
    targetRps: RATE,
    achievedRps: Math.round(iterations / seconds),
    requests: iterations,
    p50: Math.round(dur['p(50)']),
    p95: Math.round(dur['p(95)']),
    p99: Math.round(dur['p(99)']),
    errorRate: sub('checks') ? Math.round((1 - sub('checks').values.rate) * 10000) / 10000 : 0,
    dropped: sub('dropped_iterations') ? sub('dropped_iterations').values.count : 0,
    readLagP95: lagStat('lag_read_ms', 'p(95)'),
    readLagMax: lagStat('lag_read_ms', 'max'),
    pendingMax: lagStat('lag_pending_entries', 'max'),
  };
  // A step "holds" when it keeps up with the target rate, with p95 under 250 ms and under 1% errors.
  row.holds = row.achievedRps >= RATE * 0.95 && row.p95 < 250 && row.errorRate < 0.01;
  return {
    [`/results/${OUT}.json`]: JSON.stringify(row) + '\n',
    stdout: `${row.variant} ${row.scenario} target=${RATE}/s achieved=${row.achievedRps}/s p50=${row.p50}ms p95=${row.p95}ms p99=${row.p99}ms errors=${(row.errorRate * 100).toFixed(2)}% dropped=${row.dropped} lag=${row.readLagMax}ms pending=${row.pendingMax} ${row.holds ? 'HOLDS' : 'SATURATED'}\n`,
  };
}
