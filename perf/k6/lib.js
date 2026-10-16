// Shared, deterministic data generation for the seed and the benchmarks.
// Every payment is derived from (SEED, index) alone, so any VU can generate any payment and reruns are identical.

export const BASE_URL = __ENV.BASE_URL || 'http://app:8090';
export const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// mulberry32: a tiny seedable PRNG (Math.random cannot be seeded).
export function prng(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function rngFor(seed, index) {
  return prng((seed * 2654435761 + index * 40503) >>> 0);
}

function normal(rand) {
  // Box–Muller
  const u = Math.max(rand(), 1e-12);
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rand());
}

function pickWeighted(rand, items) {
  const total = items.reduce((s, it) => s + it.w, 0);
  let x = rand() * total;
  for (const it of items) {
    x -= it.w;
    if (x <= 0) return it;
  }
  return items[items.length - 1];
}

// Tax rates per jurisdiction (standard rates, as communicated on the capture event).
export const MARKETS = {
  EUR: [
    { w: 30, jurisdiction: 'NL', rate: '0.21' },
    { w: 30, jurisdiction: 'DE', rate: '0.19' },
    { w: 20, jurisdiction: 'FR', rate: '0.20' },
    { w: 10, jurisdiction: 'ES', rate: '0.21' },
    { w: 10, jurisdiction: 'IT', rate: '0.22' },
  ],
  GBP: [{ w: 1, jurisdiction: 'GB', rate: '0.20' }],
  USD: [
    { w: 45, jurisdiction: 'US-CA', rate: '0.0725' },
    { w: 35, jurisdiction: 'US-NY', rate: '0.08875' },
    { w: 20, jurisdiction: 'US-TX', rate: '0.0625' },
  ],
};

const ADJECTIVES = ['north', 'blue', 'quiet', 'bright', 'swift', 'amber', 'lunar', 'urban', 'happy', 'clever',
  'golden', 'silver', 'green', 'rapid', 'calm', 'bold', 'noble', 'pixel', 'cosmic', 'sunny'];
const NOUNS = ['yoga', 'labs', 'studio', 'fitness', 'games', 'books', 'cloud', 'notes', 'music', 'design',
  'courses', 'maps', 'photo', 'chess', 'garden', 'travel', 'recipes', 'focus', 'sleep', 'code'];

// Merchants: power-law sizes, a home market, a typical ticket, a refund propensity (a few are anomalies).
export function merchants(seed, count) {
  const rand = prng(seed ^ 0x5eed);
  const list = [];
  for (let i = 0; i < count; i++) {
    const name = `${ADJECTIVES[Math.floor(rand() * ADJECTIVES.length)]}-${NOUNS[Math.floor(rand() * NOUNS.length)]}`;
    const home = pickWeighted(rand, [{ w: 65, v: 'EU' }, { w: 20, v: 'US' }, { w: 15, v: 'UK' }]).v;
    const currencies = home === 'EU'
      ? [{ w: 85, v: 'EUR' }, { w: 15, v: 'GBP' }]
      : home === 'US' ? [{ w: 100, v: 'USD' }] : [{ w: 70, v: 'GBP' }, { w: 30, v: 'EUR' }];
    const anomaly = i % 25 === 7; // 4 in 100: refund-heavy merchants that should stand out in the UI
    list.push({
      id: `m${String(i + 1).padStart(3, '0')}-${name}`,
      w: 1 / Math.pow(i + 1, 0.9), // Zipf-like: a few big merchants, a long tail
      currencies,
      medianTicket: [999, 1999, 4900, 9900, 19900][Math.floor(rand() * 5)],
      refundRate: anomaly ? 0.25 + rand() * 0.15 : 0.02 + rand() * 0.08,
    });
  }
  return list;
}

/**
 * Payment number `index`: merchant, currency, market, amount and capture time, plus an optional refund.
 * Captures spread over the last `days` days with growth (more recent = busier) and quieter weekends.
 */
export function payment(seed, index, merchantList, days, nowMs, idPrefix) {
  const rand = rngFor(seed, index);
  const m = pickWeighted(rand, merchantList);
  const currency = pickWeighted(rand, m.currencies).v;
  const market = pickWeighted(rand, MARKETS[currency]);
  const amount = Math.max(100, Math.round(m.medianTicket * Math.exp(0.5 * normal(rand))));

  const dayMs = 86_400_000;
  let capturedMs;
  do {
    capturedMs = nowMs - days * dayMs * (1 - Math.sqrt(rand())) - 3_600_000; // linear growth, at least 1h ago
  } while ([0, 6].includes(new Date(capturedMs).getUTCDay()) && rand() < 0.35); // fewer sales at weekends

  const p = {
    paymentId: `${idPrefix}-p${index}`,
    merchantId: m.id,
    amount,
    currency,
    taxRate: market.rate,
    taxJurisdiction: market.jurisdiction,
    capturedAt: new Date(capturedMs).toISOString(),
  };

  let refund = null;
  if (rand() < m.refundRate) {
    const partial = rand() < 0.3;
    const refundedMs = Math.min(nowMs - 60_000, capturedMs + rand() * 30 * dayMs);
    refund = {
      refundId: `${idPrefix}-r${index}`,
      ...(partial ? { amount: Math.max(1, Math.round(amount * (0.1 + rand() * 0.8))) } : {}),
      refundedAt: new Date(refundedMs).toISOString(),
    };
  }
  return { payment: p, refund };
}
