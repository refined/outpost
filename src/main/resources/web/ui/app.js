// MoR Ledger Console: a thin client over the public API (vanilla JS, hash routing, Chart.js).
"use strict";

// ── Utilities ─────────────────────────────────────────────────────────────────────────────────────────────

const view = document.getElementById("view");
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const store = {
  get(k, d) { try { return localStorage.getItem(k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* storage unavailable */ } },
};

const digitsCache = {};
function digits(cur) {
  return (digitsCache[cur] ??= new Intl.NumberFormat("en", { style: "currency", currency: cur }).resolvedOptions().maximumFractionDigits);
}
const major = (minor, cur) => minor / 10 ** digits(cur);
function money(minor, cur, compact = false) {
  return new Intl.NumberFormat("en", {
    style: "currency", currency: cur,
    ...(compact ? { notation: "compact", maximumFractionDigits: 1 } : {}),
  }).format(major(minor, cur));
}
const int = (n) => Number(n).toLocaleString("en");
const pct = (x) => `${(x * 100).toFixed(1)}%`;
const day = (iso) => (iso ? iso.slice(0, 10) : "");
const moneyCell = (minor, cur) => `<td class="num${minor < 0 ? " neg" : ""}">${money(minor, cur)}</td>`;

function refundBadge(rate) {
  if (rate >= 0.2) return `<span class="badge warn" title="Refund rate above 20%">${pct(rate)}</span>`;
  return pct(rate);
}
const reconciledBadge = (ok) => ok ? `<span class="badge good">matches</span>` : `<span class="badge bad">mismatch</span>`;

/** "121.5" EUR -> 12150, string-based (no floating point). */
function toMinor(text, cur) {
  const d = digits(cur);
  const m = String(text).trim().match(/^(\d+)(?:[.,](\d*))?$/);
  if (!m) throw new Error(`"${text}" is not an amount`);
  const frac = m[2] || "";
  if (frac.length > d) throw new Error(`${cur} has ${d} decimal places`);
  return Number(m[1] + frac.padEnd(d, "0"));
}

/** "21" -> "0.21", "7.25" -> "0.0725" (string math, exact). */
function percentToRate(text) {
  const m = String(text).trim().match(/^(\d+)(?:[.,](\d+))?$/);
  if (!m) throw new Error(`"${text}" is not a percentage`);
  const digitsStr = (m[1] + (m[2] || "")).replace(/^0+(?=\d)/, "");
  const scale = (m[2] || "").length + 2;
  const padded = digitsStr.padStart(scale + 1, "0");
  return `${padded.slice(0, -scale)}.${padded.slice(-scale)}`.replace(/\.?0+$/, "") || "0";
}

const newId = (prefix) => `${prefix}_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

// ── API ───────────────────────────────────────────────────────────────────────────────────────────────────

const apiLog = [];
async function api(path, body) {
  const res = await fetch(path, body === undefined ? {} : {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (body !== undefined) apiLog.unshift({ status: res.status, path, data });
  if (!res.ok) {
    const e = new Error(data.message || res.statusText);
    e.status = res.status;
    throw e;
  }
  return data;
}

function toast(text) {
  const t = document.createElement("div");
  t.className = "toast";
  t.textContent = text;
  document.body.append(t);
  setTimeout(() => t.remove(), 4000);
}

// ── Shared state: currency and time range ────────────────────────────────────────────────────────────────

const RANGES = { "30d": { days: 30, bucket: "day" }, "90d": { days: 90, bucket: "day" }, "1y": { days: 365, bucket: "week" }, all: { days: null, bucket: "month" } };
const state = {
  currency: store.get("currency", "EUR"),
  range: store.get("range", "1y"),
  merchants: { q: "", sort: "gross", order: "desc", offset: 0, currency: "" },
};
function rangeQuery() {
  const r = RANGES[state.range];
  const from = r.days ? `&from=${new Date(Date.now() - r.days * 86_400_000).toISOString()}` : "";
  return { bucket: r.bucket, from };
}

function currencySelect(currencies, onChange) {
  const sel = document.createElement("select");
  sel.innerHTML = currencies.map((c) => `<option ${c === state.currency ? "selected" : ""}>${esc(c)}</option>`).join("");
  sel.addEventListener("change", () => { state.currency = sel.value; store.set("currency", sel.value); onChange(); });
  return sel;
}

function rangeButtons(onChange) {
  const seg = document.createElement("div");
  seg.className = "seg";
  seg.innerHTML = Object.keys(RANGES).map((k) => `<button type="button" data-r="${k}" class="${k === state.range ? "on" : ""}">${k}</button>`).join("");
  seg.addEventListener("click", (ev) => {
    const r = ev.target.dataset.r;
    if (!r) return;
    state.range = r;
    store.set("range", r);
    onChange();
  });
  return seg;
}

// ── Charts ────────────────────────────────────────────────────────────────────────────────────────────────

let charts = [];
const token = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
function destroyCharts() { charts.forEach((c) => c.destroy()); charts = []; }

function chartDefaults() {
  Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
  Chart.defaults.color = token("--ink-2");
  Chart.defaults.borderColor = token("--grid");
}

function moneyTooltip(cur) {
  return { callbacks: { label: (ctx) => `${ctx.dataset.label}: ${money(Math.round(ctx.parsed.y * 10 ** digits(cur)), cur)}` } };
}

function axisMoney(cur) {
  return { grid: { color: token("--grid") }, border: { display: false }, ticks: { callback: (v) => money(v * 10 ** digits(cur), cur, true) } };
}

/** Captured vs refunded per bucket: two series, so a legend; one money axis. */
function activityChart(canvas, points, cur) {
  charts.push(new Chart(canvas, {
    type: "bar",
    data: {
      labels: points.map((p) => p.start),
      datasets: [
        { label: "Captured", data: points.map((p) => major(p.captured.gross, cur)), backgroundColor: token("--series-1"), borderRadius: 4, borderSkipped: "start" },
        { label: "Refunded", data: points.map((p) => major(p.refunded.gross, cur)), backgroundColor: token("--series-2"), borderRadius: 4, borderSkipped: "start" },
      ],
    },
    options: {
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      plugins: { legend: { position: "top", align: "start", labels: { boxWidth: 12, boxHeight: 12 } }, tooltip: moneyTooltip(cur) },
      scales: { x: { grid: { display: false }, ticks: { maxTicksLimit: 12 } }, y: axisMoney(cur) },
      datasets: { bar: { categoryPercentage: 0.8, barPercentage: 0.9 } },
    },
  }));
}

/** One series (the title names it): what the MoR owes the merchant over time. */
function balanceChart(canvas, points, cur) {
  charts.push(new Chart(canvas, {
    type: "line",
    data: {
      labels: points.map((p) => p.start),
      datasets: [{ label: "Owed to merchant", data: points.map((p) => major(p.merchantBalance, cur)), borderColor: token("--series-1"), borderWidth: 2, pointRadius: 0, pointHoverRadius: 4, tension: 0.2 }],
    },
    options: {
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      plugins: { legend: { display: false }, tooltip: moneyTooltip(cur) },
      scales: { x: { grid: { display: false }, ticks: { maxTicksLimit: 12 } }, y: axisMoney(cur) },
    },
  }));
}

function jurisdictionChart(canvas, rows, cur) {
  charts.push(new Chart(canvas, {
    type: "bar",
    data: {
      labels: rows.map((r) => r.jurisdiction),
      datasets: [{ label: "Tax owed", data: rows.map((r) => major(r.amount, cur)), backgroundColor: token("--series-3"), borderRadius: 4, borderSkipped: "start" }],
    },
    options: {
      indexAxis: "y",
      maintainAspectRatio: false,
      plugins: { legend: { display: false }, tooltip: { callbacks: { label: (ctx) => money(Math.round(ctx.parsed.x * 10 ** digits(cur)), cur) } } },
      scales: { x: { ...axisMoney(cur), ticks: { callback: (v) => money(v * 10 ** digits(cur), cur, true) } }, y: { grid: { display: false } } },
    },
  }));
}

/**
 * Where the customers' money went: split into tax / MoR fee / merchant share, and each part into what was refunded
 * and what is still owed. Flows are clamped at zero: with the fee kept on refunds, a merchant's refunded share can
 * exceed its captured share (the merchant then owes the MoR); the note under the chart says so.
 */
function moneyFlowChart(canvas, captured, refunded, cur) {
  const nodes = {
    paid: "Customers", tax: "Tax", fee: "MoR fee", merchant: "Merchants",
    refunded: "Refunded", taxOwed: "Tax owed", feeKept: "Fee kept", merchantOwed: "Merchants owed",
  };
  const longNames = { paid: "Customers paid", merchant: "Merchant share", feeKept: "MoR fee revenue", merchantOwed: "Owed to merchants" };
  const name = (k) => longNames[k] || nodes[k];
  const color = {
    paid: token("--neutral-node"), refunded: token("--neutral-node"),
    tax: token("--series-3"), taxOwed: token("--series-3"),
    fee: token("--series-2"), feeKept: token("--series-2"),
    merchant: token("--series-1"), merchantOwed: token("--series-1"),
  };
  const flows = [
    ["paid", "tax", captured.tax], ["paid", "fee", captured.fee], ["paid", "merchant", captured.merchant],
    ["tax", "refunded", refunded.tax], ["tax", "taxOwed", captured.tax - refunded.tax],
    ["fee", "refunded", refunded.fee], ["fee", "feeKept", captured.fee - refunded.fee],
    ["merchant", "refunded", Math.min(refunded.merchant, captured.merchant)], ["merchant", "merchantOwed", captured.merchant - refunded.merchant],
  ].filter(([, , v]) => v > 0).map(([from, to, v]) => ({ from, to, flow: major(v, cur) }));

  charts.push(new Chart(canvas, {
    type: "sankey",
    data: {
      datasets: [{
        label: "Money flow",
        data: flows,
        labels: nodes,
        colorFrom: (c) => color[c.dataset.data[c.dataIndex].from],
        colorTo: (c) => color[c.dataset.data[c.dataIndex].to],
        colorMode: "to",
        alpha: 0.55,
        borderWidth: 0,
        nodeWidth: 12,
        color: token("--ink"),
        font: { size: 12 },
        size: "max",
      }],
    },
    options: {
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: (ctx) => { const d = ctx.raw; return `${name(d.from)} → ${name(d.to)}: ${money(Math.round(d.flow * 10 ** digits(cur)), cur)}`; } } },
      },
    },
  }));
}

function dataTable(points, cur) {
  return `<details class="data-table"><summary>Show data table</summary><div class="scroll"><table>
    <thead><tr><th>Period</th><th class="num">Captured</th><th class="num">Refunded</th><th class="num">Net</th><th class="num">Owed to merchant(s)</th></tr></thead>
    <tbody>${points.map((p) => `<tr><td>${esc(p.start)}</td>${moneyCell(p.captured.gross, cur)}${moneyCell(p.refunded.gross, cur)}${moneyCell(p.netGross, cur)}${moneyCell(p.merchantBalance, cur)}</tr>`).join("")}</tbody>
  </table></div></details>`;
}

// ── Pages ─────────────────────────────────────────────────────────────────────────────────────────────────

const kpi = (label, value, foot = "", neg = false) =>
  `<div class="kpi"><div class="label">${esc(label)}</div><div class="value${neg ? " neg" : ""}">${value}</div><div class="foot">${foot}</div></div>`;

async function overviewPage() {
  const all = await api("/v1/reports/balances");
  const currencies = [...new Set(all.mor.map((m) => m.currency))];
  if (currencies.length === 0) {
    view.innerHTML = `<div class="card"><h2>No data yet</h2><p class="hint">Record payments on the Simulate page, or load the demo dataset:
      <code>docker compose --profile tools run --rm k6 run seed.js</code></p></div>`;
    return;
  }
  if (!currencies.includes(state.currency)) state.currency = currencies[0];
  const cur = state.currency;
  const { bucket, from } = rangeQuery();
  const [summary, ts, top] = await Promise.all([
    api(`/v1/analytics/summary?currency=${cur}${from}`),
    api(`/v1/analytics/timeseries?currency=${cur}&bucket=${bucket}${from}`),
    api(`/v1/analytics/merchants?currency=${cur}&sort=gross&limit=10`),
  ]);
  const s = summary.currencies[0] || { captured: { count: 0, gross: 0 }, refunded: { count: 0, gross: 0, tax: 0, fee: 0, merchant: 0 }, refundRate: 0, merchants: 0 };
  const mor = all.mor.find((m) => m.currency === cur);
  const taxRows = all.taxAuthorities.filter((t) => t.currency === cur).map((t) => ({ jurisdiction: t.jurisdiction, amount: t.amountOwed })).sort((a, b) => b.amount - a.amount);
  const merchantRows = all.merchants.filter((m) => m.currency === cur);
  const taxOwed = taxRows.reduce((a, t) => a + t.amount, 0);
  const owed = merchantRows.reduce((a, m) => a + m.balance, 0);
  const negative = merchantRows.filter((m) => m.balance < 0).length;

  view.innerHTML = `
    <div class="page-head">
      <div><h1>Overview</h1><p class="sub">What the MoR owes right now, and what happened in the selected period.</p></div>
      <div class="filters" id="filters"></div>
    </div>
    <div class="section-title">Owed now · from the ledger</div>
    <div class="kpis">
      ${kpi("Owed to tax authorities", money(taxOwed, cur), `${taxRows.length} jurisdictions`)}
      ${kpi("Owed to merchants", money(owed, cur), `${merchantRows.length} merchants${negative ? ` · ${negative} owe the MoR` : ""}`)}
      ${kpi("MoR fee revenue", money(mor.feeRevenue, cur), "kept after refunds")}
      ${kpi("Held at the PSP", money(mor.pspClearing, cur), "collected minus refunded")}
      ${kpi("Ledger integrity", all.integrity.balanced ? `<span class="badge good">balanced</span>` : `<span class="badge bad">unbalanced</span>`, "Σ debits = Σ credits")}
    </div>
    <div class="section-title">Activity · ${esc(state.range === "all" ? "all time" : `last ${state.range}`)}</div>
    <div class="kpis">
      ${kpi("Captured", money(s.captured.gross, cur, true), `${int(s.captured.count)} payments`)}
      ${kpi("Refunded", money(s.refunded.gross, cur, true), `${int(s.refunded.count)} refunds · ${pct(s.refundRate)} of volume`)}
      ${kpi("Tax collected (net)", money((s.captured.tax ?? 0) - s.refunded.tax, cur, true))}
      ${kpi("Active merchants", int(s.merchants))}
    </div>
    <div class="grid two" style="margin-top:16px">
      <div class="card"><div class="card-head"><h2>Captured vs refunded</h2><span class="hint">per ${esc(bucket)}</span></div>
        <div class="chart"><canvas id="c-activity"></canvas></div>${dataTable(ts.points, cur)}</div>
      <div class="card"><div class="card-head"><h2>Where the money went</h2><span class="hint">selected period</span></div>
        <div class="chart"><canvas id="c-flow"></canvas></div></div>
      <div class="card"><div class="card-head"><h2>Tax owed by jurisdiction</h2><span class="hint">now</span></div>
        <div class="chart short"><canvas id="c-tax"></canvas></div></div>
      <div class="card"><div class="card-head"><h2>Top merchants</h2><span class="hint">by volume, all time</span><span class="spacer"></span><a href="#/merchants">All merchants →</a></div>
        <div class="scroll"><table><thead><tr><th>Merchant</th><th class="num">Payments</th><th class="num">Volume</th><th class="num">Refund rate</th><th class="num">Owed</th></tr></thead>
        <tbody>${top.merchants.map((m) => `<tr class="click" data-m="${esc(m.merchantId)}"><td>${esc(m.merchantId)}</td><td class="num">${int(m.captured.count)}</td>${moneyCell(m.captured.gross, cur)}<td class="num">${refundBadge(m.refundRate)}</td>${moneyCell(m.balance, cur)}</tr>`).join("")}</tbody></table></div></div>
    </div>`;

  const filters = document.getElementById("filters");
  filters.append(currencySelect(currencies, render), rangeButtons(render));
  view.querySelectorAll("tr[data-m]").forEach((tr) => tr.addEventListener("click", () => { location.hash = `#/merchants/${encodeURIComponent(tr.dataset.m)}`; }));
  activityChart(document.getElementById("c-activity"), ts.points, cur);
  moneyFlowChart(document.getElementById("c-flow"), s.captured, s.refunded, cur);
  jurisdictionChart(document.getElementById("c-tax"), taxRows, cur);
}

const MERCHANT_COLUMNS = [
  ["merchant", "Merchant", ""], ["", "Ccy", ""], ["payments", "Payments", "num"], ["gross", "Volume", "num"], ["refunded", "Refunded", "num"],
  ["refundRate", "Refund rate", "num"], ["fee", "MoR fee", "num"], ["", "Tax", "num"], ["balance", "Owed", "num"], ["", "Ledger check", ""], ["lastPayment", "Last payment", ""],
];

async function merchantsPage() {
  const st = state.merchants;
  const qs = new URLSearchParams({ sort: st.sort, order: st.order, limit: "50", offset: String(st.offset) });
  if (st.q) qs.set("q", st.q);
  if (st.currency) qs.set("currency", st.currency);
  const page = await api(`/v1/analytics/merchants?${qs}`);

  view.innerHTML = `
    <div class="page-head">
      <div><h1>Merchants</h1><p class="sub">What each merchant did: volume, refunds, fees, tax and what the MoR owes them. "Ledger check" compares the business records with the merchant's ledger balance.</p></div>
      <div class="filters">
        <input id="m-q" placeholder="Search merchant…" value="${esc(st.q)}">
        <select id="m-cur"><option value="">All currencies</option>${["EUR", "GBP", "USD"].map((c) => `<option ${c === st.currency ? "selected" : ""}>${c}</option>`).join("")}</select>
      </div>
    </div>
    <div class="card"><div class="scroll"><table>
      <thead><tr>${MERCHANT_COLUMNS.map(([key, label, cls]) => `<th class="${cls}${key ? " sortable" : ""}${key === st.sort ? " sorted" : ""}" data-sort="${key}">${label}${key === st.sort ? (st.order === "desc" ? " ↓" : " ↑") : ""}</th>`).join("")}</tr></thead>
      <tbody>${page.merchants.map((m) => `<tr class="click" data-m="${esc(m.merchantId)}">
        <td>${esc(m.merchantId)}</td><td>${m.currency}</td><td class="num">${int(m.captured.count)}</td>
        ${moneyCell(m.captured.gross, m.currency)}${moneyCell(m.refunded.gross, m.currency)}<td class="num">${refundBadge(m.refundRate)}</td>
        ${moneyCell(m.net.fee, m.currency)}${moneyCell(m.net.tax, m.currency)}${moneyCell(m.balance, m.currency)}
        <td>${reconciledBadge(m.reconciled)}</td><td>${day(m.lastPaymentAt)}</td></tr>`).join("") || `<tr><td colspan="11" class="empty">No merchants</td></tr>`}</tbody>
    </table></div>
    <div class="pager"><span>${int(st.offset + 1)}–${int(Math.min(st.offset + 50, page.total))} of ${int(page.total)}</span>
      <button class="ghost small" id="m-prev" ${st.offset === 0 ? "disabled" : ""}>← Prev</button>
      <button class="ghost small" id="m-next" ${st.offset + 50 >= page.total ? "disabled" : ""}>Next →</button></div></div>`;

  let timer;
  document.getElementById("m-q").addEventListener("input", (ev) => {
    clearTimeout(timer);
    timer = setTimeout(() => { st.q = ev.target.value.trim(); st.offset = 0; render().then(() => { const q = document.getElementById("m-q"); q.focus(); q.setSelectionRange(q.value.length, q.value.length); }); }, 300);
  });
  document.getElementById("m-cur").addEventListener("change", (ev) => { st.currency = ev.target.value; st.offset = 0; render(); });
  view.querySelectorAll("th[data-sort]").forEach((th) => th.addEventListener("click", () => {
    if (!th.dataset.sort) return;
    st.order = st.sort === th.dataset.sort && st.order === "desc" ? "asc" : "desc";
    st.sort = th.dataset.sort;
    st.offset = 0;
    render();
  }));
  document.getElementById("m-prev").addEventListener("click", () => { st.offset = Math.max(0, st.offset - 50); render(); });
  document.getElementById("m-next").addEventListener("click", () => { st.offset += 50; render(); });
  view.querySelectorAll("tr[data-m]").forEach((tr) => tr.addEventListener("click", () => { location.hash = `#/merchants/${encodeURIComponent(tr.dataset.m)}`; }));
}

const merchantView = { id: null, currency: null, offset: 0, status: "" };

async function merchantPage(id) {
  if (merchantView.id !== id) Object.assign(merchantView, { id, currency: null, offset: 0, status: "" });
  const detail = await api(`/v1/analytics/merchants/${encodeURIComponent(id)}`);
  const row = detail.currencies.find((c) => c.currency === merchantView.currency) || detail.currencies[0];
  const cur = (merchantView.currency = row.currency);
  const { bucket, from } = rangeQuery();
  const qs = new URLSearchParams({ merchantId: id, limit: "20", offset: String(merchantView.offset) });
  if (merchantView.status) qs.set("status", merchantView.status);
  const [ts, payments, journal] = await Promise.all([
    api(`/v1/analytics/timeseries?currency=${cur}&merchantId=${encodeURIComponent(id)}&bucket=${bucket}${from}`),
    api(`/v1/payments?${qs}`),
    api(`/v1/journal?merchantId=${encodeURIComponent(id)}&limit=8`),
  ]);
  const jurisdictions = detail.jurisdictions.filter((j) => j.currency === cur);

  view.innerHTML = `
    <div class="page-head">
      <div><p class="sub"><a href="#/merchants">← Merchants</a></p><h1>${esc(id)}</h1>
        <p class="sub">First payment ${day(row.firstPaymentAt)} · last ${day(row.lastPaymentAt)}</p></div>
      <div class="filters" id="filters">
        ${detail.currencies.length > 1 ? `<div class="seg" id="m-curs">${detail.currencies.map((c) => `<button type="button" data-c="${c.currency}" class="${c.currency === cur ? "on" : ""}">${c.currency}</button>`).join("")}</div>` : `<span class="pill">${cur}</span>`}
      </div>
    </div>
    <div class="kpis">
      ${kpi("Owed to the merchant", money(row.ledgerBalance ?? row.balance, cur), reconciledBadge(row.reconciled), (row.ledgerBalance ?? row.balance) < 0)}
      ${kpi("Volume", money(row.captured.gross, cur), `${int(row.captured.count)} payments`)}
      ${kpi("Refunded", money(row.refunded.gross, cur), `${int(row.refunded.count)} refunds · ${pct(row.refundRate)}`)}
      ${kpi("MoR fee (net)", money(row.net.fee, cur))}
      ${kpi("Tax (net)", money(row.net.tax, cur), `${jurisdictions.length} jurisdictions`)}
    </div>
    <div class="grid two" style="margin-top:16px">
      <div class="card"><div class="card-head"><h2>Captured vs refunded</h2><span class="hint">per ${esc(bucket)}</span><span class="spacer"></span><span id="range"></span></div>
        <div class="chart"><canvas id="c-activity"></canvas></div>${dataTable(ts.points, cur)}</div>
      <div class="card"><div class="card-head"><h2>Owed to the merchant over time</h2><span class="hint">running balance</span></div>
        <div class="chart"><canvas id="c-balance"></canvas></div></div>
      <div class="card"><div class="card-head"><h2>Where the money went</h2><span class="hint">all time</span></div>
        <div class="chart"><canvas id="c-flow"></canvas></div>
        ${row.refunded.merchant > row.captured.merchant ? `<p class="hint">Refunds exceed the merchant's share: the MoR kept its fee, so the merchant owes it the difference.</p>` : ""}</div>
      <div class="card"><h2>By jurisdiction</h2><p class="hint">all time</p><div class="scroll"><table>
        <thead><tr><th>Jurisdiction</th><th class="num">Payments</th><th class="num">Volume</th><th class="num">Tax collected</th><th class="num">Tax refunded</th><th class="num">Tax owed</th></tr></thead>
        <tbody>${jurisdictions.map((j) => `<tr><td>${esc(j.jurisdiction)}</td><td class="num">${int(j.captured.count)}</td>${moneyCell(j.captured.gross, cur)}${moneyCell(j.captured.tax, cur)}${moneyCell(j.refunded.tax, cur)}${moneyCell(j.captured.tax - j.refunded.tax, cur)}</tr>`).join("")}</tbody></table></div></div>
    </div>
    <div class="card" style="margin-top:16px">
      <div class="card-head"><h2>Payments</h2><span class="hint">latest capture first · all currencies</span><span class="spacer"></span>
        <select id="p-status"><option value="">All statuses</option>${["CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED"].map((s) => `<option value="${s}" ${s === merchantView.status ? "selected" : ""}>${s.replace("_", " ").toLowerCase()}</option>`).join("")}</select></div>
      <div class="scroll"><table><thead><tr><th>Payment</th><th>Captured</th><th>Jurisdiction</th><th class="num">Gross</th><th class="num">Tax</th><th class="num">Fee</th><th class="num">Merchant</th><th class="num">Refundable</th><th>Status</th><th></th></tr></thead>
      <tbody>${payments.payments.map((p) => `<tr><td class="mono">${esc(p.paymentId)}</td><td>${day(p.capturedAt)}</td><td>${esc(p.taxJurisdiction)}</td>
        ${moneyCell(p.amount, p.currency)}${moneyCell(p.taxAmount, p.currency)}${moneyCell(p.feeAmount, p.currency)}${moneyCell(p.merchantAmount, p.currency)}${moneyCell(p.refundable.amount, p.currency)}
        <td>${p.status.replace("_", " ").toLowerCase()}</td>
        <td>${p.refundable.amount > 0 ? `<button class="ghost small" data-refund="${esc(p.paymentId)}" data-cur="${p.currency}" data-left="${p.refundable.amount}">Refund…</button>` : ""}</td></tr>`).join("") || `<tr><td colspan="10" class="empty">No payments</td></tr>`}</tbody></table></div>
      <div class="pager"><span>${int(merchantView.offset + 1)}–${int(Math.min(merchantView.offset + 20, payments.total))} of ${int(payments.total)}</span>
        <button class="ghost small" id="p-prev" ${merchantView.offset === 0 ? "disabled" : ""}>← Prev</button>
        <button class="ghost small" id="p-next" ${merchantView.offset + 20 >= payments.total ? "disabled" : ""}>Next →</button></div>
    </div>
    <div class="card" style="margin-top:16px">
      <div class="card-head"><h2>Latest journal entries</h2><span class="hint">entries touching merchant_payable:${esc(id)}</span><span class="spacer"></span><a href="#/ledger">Ledger →</a></div>
      ${journalTable(journal.entries)}
    </div>`;

  document.getElementById("range").append(rangeButtons(render));
  document.getElementById("m-curs")?.addEventListener("click", (ev) => { if (ev.target.dataset.c) { merchantView.currency = ev.target.dataset.c; render(); } });
  document.getElementById("p-status").addEventListener("change", (ev) => { merchantView.status = ev.target.value; merchantView.offset = 0; render(); });
  document.getElementById("p-prev").addEventListener("click", () => { merchantView.offset = Math.max(0, merchantView.offset - 20); render(); });
  document.getElementById("p-next").addEventListener("click", () => { merchantView.offset += 20; render(); });
  view.querySelectorAll("button[data-refund]").forEach((b) => b.addEventListener("click", () => refundPrompt(b.dataset.refund, b.dataset.cur, Number(b.dataset.left))));
  activityChart(document.getElementById("c-activity"), ts.points, cur);
  balanceChart(document.getElementById("c-balance"), ts.points, cur);
  moneyFlowChart(document.getElementById("c-flow"), row.captured, row.refunded, cur);
}

async function refundPrompt(paymentId, cur, left) {
  const answer = prompt(`Refund ${paymentId}: amount in ${cur} (empty = everything left, ${money(left, cur)})`, "");
  if (answer === null) return;
  try {
    const body = { refundId: newId("ref") };
    if (answer.trim()) body.amount = toMinor(answer, cur);
    const r = await api(`/v1/payments/${encodeURIComponent(paymentId)}/refunds`, body);
    toast(`Refunded ${money(r.refund.amount, cur)}: tax ${money(r.refund.taxAmount, cur)}, fee ${money(r.refund.feeAmount, cur)}, merchant ${money(r.refund.merchantAmount, cur)}`);
  } catch (e) {
    toast(`Refund failed: ${e.message}`);
  }
  render();
}

function journalTable(entries) {
  if (!entries.length) return `<p class="empty">No entries</p>`;
  const rows = entries.flatMap((e) => e.postings.map((p, i) => `<tr class="${i === 0 ? "entry-start" : ""}">
      <td>${i === 0 ? e.id : ""}</td><td>${i === 0 ? e.kind : ""}</td>
      <td class="mono">${i === 0 ? esc(e.refundId ? `${e.paymentId} / ${e.refundId}` : e.paymentId) : ""}</td>
      <td>${i === 0 ? esc(e.effectiveAt.replace("T", " ").slice(0, 16)) : ""}</td>
      <td class="mono">${esc(p.account)}</td>
      <td class="num">${p.debit ? money(p.debit, p.currency) : ""}</td><td class="num">${p.credit ? money(p.credit, p.currency) : ""}</td></tr>`));
  return `<div class="scroll"><table><thead><tr><th>#</th><th>Kind</th><th>Payment / refund</th><th>Effective</th><th>Account</th><th class="num">Debit</th><th class="num">Credit</th></tr></thead><tbody>${rows.join("")}</tbody></table></div>`;
}

const ledgerView = { currency: "", paymentId: "", before: [] };

async function ledgerPage() {
  const lv = ledgerView;
  const before = lv.before[lv.before.length - 1];
  const jq = new URLSearchParams({ limit: "25" });
  if (lv.paymentId) jq.set("paymentId", lv.paymentId);
  if (before) jq.set("before", String(before));
  const [health, verify, accounts, journal] = await Promise.all([
    api("/health"),
    api("/v1/ledger/verify").catch((e) => ({ error: e.message })),
    api(`/v1/ledger/accounts${lv.currency ? `?currency=${lv.currency}` : ""}`),
    api(`/v1/journal?${jq}`),
  ]);
  const verifyBadge = verify.error ? `<span class="badge bad">${esc(verify.error)}</span>`
    : verify.ok ? `<span class="badge good">stored balances = Σ postings</span>` : `<span class="badge bad">${verify.mismatches.length} mismatches</span>`;

  view.innerHTML = `
    <div class="page-head"><div><h1>Ledger</h1><p class="sub">The double-entry ledger itself: every account and every journal entry.</p></div></div>
    <div class="kpis">
      ${kpi("Engine", esc(health.ledger))}
      ${kpi("Reconciliation", verifyBadge, "checked on every page load")}
      ${kpi("Accounts", int(accounts.accounts.length), lv.currency || "all currencies")}
    </div>
    <div class="grid ledger" style="margin-top:16px">
      <div class="card"><div class="card-head"><h2>Accounts</h2><span class="hint">balance on the account's normal side</span><span class="spacer"></span>
        <select id="l-cur"><option value="">All currencies</option>${["EUR", "GBP", "USD"].map((c) => `<option ${c === lv.currency ? "selected" : ""}>${c}</option>`).join("")}</select></div>
        <div class="scroll" style="max-height:640px;overflow-y:auto"><table><thead><tr><th>Account</th><th>Ccy</th><th class="num">Balance</th></tr></thead>
        <tbody>${accounts.accounts.map((a) => `<tr title="normal side: ${a.normalSide.toLowerCase()}"><td class="mono">${esc(a.code)}</td><td>${a.currency}</td>${moneyCell(a.balance, a.currency)}</tr>`).join("")}</tbody></table></div></div>
      <div class="card"><div class="card-head"><h2>Journal</h2><span class="hint">most recent first</span><span class="spacer"></span>
        <input id="l-pay" placeholder="Filter by payment id" value="${esc(lv.paymentId)}"></div>
        ${journalTable(journal.entries)}
        <div class="pager"><button class="ghost small" id="l-newer" ${lv.before.length ? "" : "disabled"}>← Newer</button>
          <button class="ghost small" id="l-older" ${journal.entries.length < 25 ? "disabled" : ""}>Older →</button></div></div>
    </div>`;

  document.getElementById("l-cur").addEventListener("change", (ev) => { lv.currency = ev.target.value; render(); });
  document.getElementById("l-pay").addEventListener("change", (ev) => { lv.paymentId = ev.target.value.trim(); lv.before = []; render(); });
  document.getElementById("l-newer").addEventListener("click", () => { lv.before.pop(); render(); });
  document.getElementById("l-older").addEventListener("click", () => { lv.before.push(journal.entries[journal.entries.length - 1].id); render(); });
}

async function simulatePage() {
  view.innerHTML = `
    <div class="page-head"><div><h1>Simulate</h1><p class="sub">Send capture and refund events through the public API, as a PSP webhook would.</p></div></div>
    <div class="grid two">
      <div class="card"><h2>Capture a payment</h2><p class="hint">Amount is what the customer paid, tax included.</p>
        <form id="f-capture" class="stack">
          <label>Merchant <input name="merchantId" value="acme" required></label>
          <div class="row"><label>Amount <input name="amount" value="121.00" inputmode="decimal" required></label>
            <label>Currency <select name="currency"><option>EUR</option><option>USD</option><option>GBP</option><option>SEK</option><option>JPY</option></select></label></div>
          <div class="row"><label>Tax rate % <input name="taxRate" value="21" inputmode="decimal" required></label>
            <label>Jurisdiction <input name="taxJurisdiction" value="NL" required></label></div>
          <div class="row"><label>Captured at <span class="hint">(empty = now)</span><input name="capturedAt" type="datetime-local"></label>
            <label>Payment id <span class="hint">(empty = generated)</span><input name="paymentId" placeholder="pay_…"></label></div>
          <div class="row"><button type="submit">Capture</button><button type="button" id="replay" class="ghost" disabled>Replay last</button></div>
        </form></div>
      <div class="card"><h2>Refund</h2><p class="hint">Empty amount = everything that is left.</p>
        <form id="f-refund" class="stack">
          <label>Payment id <input name="paymentId" required placeholder="pay_…"></label>
          <div class="row"><label>Amount <input name="amount" inputmode="decimal"></label><label>Currency <select name="currency"><option>EUR</option><option>USD</option><option>GBP</option><option>SEK</option><option>JPY</option></select></label></div>
          <label>Refunded at <span class="hint">(empty = now)</span><input name="refundedAt" type="datetime-local"></label>
          <div class="row"><button type="submit">Refund</button></div>
        </form>
        <h2 style="margin-top:20px">Demo scenario</h2>
        <p class="hint">Two merchants, two currencies; a partial and a full refund, then a replay (200), a conflict (409) and an over-refund (422).</p>
        <button type="button" id="scenario">Run scenario</button></div>
    </div>
    <div class="card" style="margin-top:16px"><h2>API log</h2><ol id="log" class="log"></ol></div>`;

  const renderLog = () => {
    document.getElementById("log").innerHTML = apiLog.slice(0, 50).map((l) => {
      const d = l.data;
      const msg = d.error ? `${d.error}: ${d.message}` : d.refund ? `refund ${money(d.refund.amount, d.payment.currency)}` : d.paymentId ? `${d.paymentId}: tax ${money(d.taxAmount, d.currency)}, fee ${money(d.feeAmount, d.currency)}, merchant ${money(d.merchantAmount, d.currency)}` : "";
      return `<li class="${l.status < 300 ? "ok" : "bad"}"><div>${l.status} POST ${esc(l.path)}</div><div class="msg">${esc(msg)}</div></li>`;
    }).join("") || `<li class="empty">Nothing sent yet</li>`;
  };
  const guarded = (fn) => async (ev) => { ev?.preventDefault(); try { await fn(ev); } catch (e) { if (!e.status) toast(e.message); } renderLog(); };
  let last = null;

  document.getElementById("f-capture").addEventListener("submit", guarded(async (ev) => {
    const f = new FormData(ev.target);
    const cur = f.get("currency");
    last = {
      paymentId: f.get("paymentId").trim() || newId("pay"), merchantId: f.get("merchantId").trim(), amount: toMinor(f.get("amount"), cur), currency: cur,
      taxRate: percentToRate(f.get("taxRate")), taxJurisdiction: f.get("taxJurisdiction").trim().toUpperCase(),
      ...(f.get("capturedAt") ? { capturedAt: new Date(f.get("capturedAt")).toISOString() } : {}),
    };
    document.getElementById("replay").disabled = false;
    await api("/v1/payments", last);
  }));
  document.getElementById("replay").addEventListener("click", guarded(() => api("/v1/payments", last)));
  document.getElementById("f-refund").addEventListener("submit", guarded(async (ev) => {
    const f = new FormData(ev.target);
    const body = { refundId: newId("ref") };
    if (f.get("amount").trim()) body.amount = toMinor(f.get("amount"), f.get("currency"));
    if (f.get("refundedAt")) body.refundedAt = new Date(f.get("refundedAt")).toISOString();
    await api(`/v1/payments/${encodeURIComponent(f.get("paymentId").trim())}/refunds`, body);
  }));
  document.getElementById("scenario").addEventListener("click", guarded(async () => {
    const run = Date.now().toString(36);
    const p1 = { paymentId: `pay_${run}_1`, merchantId: "acme", amount: 12100, currency: "EUR", taxRate: "0.21", taxJurisdiction: "NL" };
    const p2 = { paymentId: `pay_${run}_2`, merchantId: "globex", amount: 5000, currency: "USD", taxRate: "0.0725", taxJurisdiction: "US-CA" };
    const calls = [
      () => api("/v1/payments", p1), () => api("/v1/payments", p2),
      () => api(`/v1/payments/${p1.paymentId}/refunds`, { refundId: `ref_${run}_1a`, amount: 6050 }),
      () => api(`/v1/payments/${p2.paymentId}/refunds`, { refundId: `ref_${run}_2` }),
      () => api("/v1/payments", p1), () => api("/v1/payments", { ...p1, amount: 999 }),
      () => api(`/v1/payments/${p1.paymentId}/refunds`, { refundId: `ref_${run}_1b`, amount: 999999 }),
    ];
    for (const call of calls) await call().catch(() => {});
    toast("Scenario done: see the API log, the Overview and the acme / globex merchant pages.");
  }));
  renderLog();
}

// ── Router ────────────────────────────────────────────────────────────────────────────────────────────────

const ROUTES = [
  [/^#?\/?$/, "overview", overviewPage],
  [/^#\/merchants\/?$/, "merchants", merchantsPage],
  [/^#\/merchants\/(.+)$/, "merchants", merchantPage],
  [/^#\/ledger\/?$/, "ledger", ledgerPage],
  [/^#\/simulate\/?$/, "simulate", simulatePage],
];

let rendering = 0;
let lastHash = null;
async function render() {
  const ticket = ++rendering;
  const hash = location.hash || "#/";
  if (hash !== lastHash) { window.scrollTo(0, 0); lastHash = hash; } // new page: start at the top; same page: keep the position
  const [, name, page, args] = ROUTES.map(([re, n, fn]) => { const m = hash.match(re); return m && [m, n, fn, m.slice(1).map(decodeURIComponent)]; }).find(Boolean)
    || [null, "overview", overviewPage, []];
  document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("active", a.dataset.route === name));
  try {
    destroyCharts();
    chartDefaults();
    await page(...args);
  } catch (e) {
    if (ticket === rendering) view.innerHTML = `<div class="card"><h2>Could not load this page</h2><p class="hint">${esc(e.message)}</p></div>`;
  }
}

window.addEventListener("hashchange", render);
window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", render);
api("/health").then((h) => { document.getElementById("engine").textContent = h.ledger; }).catch(() => { document.getElementById("engine").textContent = "API down"; });
render();
