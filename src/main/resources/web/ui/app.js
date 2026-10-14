// MoR Ledger Console: a thin client over the public API. No framework, no build step.
"use strict";

const $ = (sel) => document.querySelector(sel);
let lastCapture = null;

// ── Money helpers: the API speaks integer minor units; the UI speaks major units. ──────────────────────────

function fractionDigits(currency) {
    return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits;
}

/** "121.5" EUR -> 12150. String-based, so no floating-point rounding. */
function toMinor(text, currency) {
    const digits = fractionDigits(currency);
    const m = String(text).trim().match(/^(\d+)(?:[.,](\d*))?$/);
    if (!m) throw new Error(`"${text}" is not an amount`);
    const frac = (m[2] || "");
    if (frac.length > digits) throw new Error(`${currency} has ${digits} decimal places`);
    return Number(m[1] + frac.padEnd(digits, "0"));
}

function money(minor, currency) {
    return new Intl.NumberFormat("en", { style: "currency", currency }).format(minor / 10 ** fractionDigits(currency));
}

/** "21" -> "0.21", "25.5" -> "0.255" (string math, exact). */
function percentToRate(text) {
    const m = String(text).trim().match(/^(\d+)(?:[.,](\d+))?$/);
    if (!m) throw new Error(`"${text}" is not a percentage`);
    const digits = (m[1] + (m[2] || "")).replace(/^0+(?=\d)/, "");
    const scale = (m[2] || "").length + 2;
    const padded = digits.padStart(scale + 1, "0");
    const rate = `${padded.slice(0, -scale)}.${padded.slice(-scale)}`.replace(/\.?0+$/, "");
    return rate === "" ? "0" : rate;
}

function isoOrNull(localValue) {
    return localValue ? new Date(localValue).toISOString() : null;
}

function newId(prefix) {
    return `${prefix}_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

// ── API calls, each one logged in the console panel. ───────────────────────────────────────────────────────

async function api(method, path, body) {
    const res = await fetch(path, {
        method,
        headers: body ? { "Content-Type": "application/json" } : {},
        body: body ? JSON.stringify(body) : undefined,
    });
    const data = await res.json().catch(() => ({}));
    if (method !== "GET") log(method, path, res.status, data);
    return { status: res.status, ok: res.ok, data };
}

function log(method, path, status, data) {
    const li = document.createElement("li");
    li.className = status < 300 ? "ok" : "bad";
    const summary = data.error ? `${data.error}: ${data.message}` : describe(data);
    li.innerHTML = `<div>${status} ${method} ${path}</div><div class="msg"></div>`;
    li.querySelector(".msg").textContent = summary;
    $("#log").prepend(li);
}

function describe(data) {
    if (data.refund) {
        const r = data.refund, c = data.payment.currency;
        return `refund ${money(r.amount, c)} = tax ${money(r.taxAmount, c)} + fee ${money(r.feeAmount, c)} + merchant ${money(r.merchantAmount, c)}`;
    }
    if (data.paymentId) {
        const c = data.currency;
        return `${data.paymentId}: tax ${money(data.taxAmount, c)}, fee ${money(data.feeAmount, c)}, merchant ${money(data.merchantAmount, c)}`;
    }
    return "";
}

// ── Rendering ─────────────────────────────────────────────────────────────────────────────────────────────

function cell(text, cls) {
    const td = document.createElement("td");
    if (cls) td.className = cls;
    td.textContent = text;
    return td;
}

function fill(table, rows, emptyText, colspan) {
    const tbody = $(table).querySelector("tbody");
    tbody.replaceChildren();
    if (rows.length === 0) {
        const tr = document.createElement("tr");
        const td = cell(emptyText, "empty");
        td.colSpan = colspan;
        tr.append(td);
        tbody.append(tr);
        return;
    }
    rows.forEach((r) => tbody.append(r));
}

function row(...cells) {
    const tr = document.createElement("tr");
    tr.append(...cells);
    return tr;
}

function amountCell(minor, currency) {
    return cell(money(minor, currency), "num" + (minor < 0 ? " neg" : ""));
}

async function refresh() {
    const asOf = isoOrNull($("#as-of").value);
    const [report, payments, journal] = await Promise.all([
        api("GET", "/v1/reports/balances" + (asOf ? `?asOf=${encodeURIComponent(asOf)}` : "")),
        api("GET", "/v1/payments?limit=50"),
        api("GET", "/v1/journal?limit=30"),
    ]);

    const b = report.data;
    fill("#tax-table", b.taxAuthorities.map((t) => row(cell(`${t.jurisdiction} · ${t.currency}`), amountCell(t.amountOwed, t.currency))), "nothing yet", 2);
    fill("#merchant-table", b.merchants.map((m) => row(cell(`${m.merchantId} · ${m.currency}`), amountCell(m.balance, m.currency))), "nothing yet", 2);
    fill("#mor-table", b.mor.map((m) => row(cell(m.currency), amountCell(m.feeRevenue, m.currency), amountCell(m.pspClearing, m.currency))), "nothing yet", 3);
    const integrity = $("#integrity");
    integrity.textContent = b.integrity.balanced ? "ledger balanced" : `UNBALANCED: ${b.integrity.unbalancedCurrencies.join(", ")}`;
    integrity.className = "pill " + (b.integrity.balanced ? "ok" : "bad");

    const list = payments.data.payments;
    fill("#payments-table", list.map((p) => row(
        cell(p.paymentId, "mono"), cell(p.merchantId),
        amountCell(p.amount, p.currency), amountCell(p.taxAmount, p.currency), amountCell(p.feeAmount, p.currency),
        amountCell(p.merchantAmount, p.currency), amountCell(p.refundable.amount, p.currency),
        cell(p.status.replace("_", " ").toLowerCase()), cell(p.refundFeePolicy === "MOR_KEEPS_FEE" ? "MoR keeps fee" : "fee returned"),
    )), "no payments yet", 9);

    const select = $("#refund-payment");
    const selected = select.value;
    select.replaceChildren(...list.filter((p) => p.refundable.amount > 0).map((p) => {
        const o = document.createElement("option");
        o.value = p.paymentId;
        o.textContent = `${p.paymentId} · ${p.merchantId} · ${money(p.refundable.amount, p.currency)} left`;
        o.dataset.currency = p.currency;
        o.dataset.refundable = p.refundable.amount;
        return o;
    }));
    if ([...select.options].some((o) => o.value === selected)) select.value = selected;
    showRefundable();

    const rows = [];
    for (const e of journal.data.entries) {
        e.postings.forEach((p, i) => {
            const tr = row(
                cell(i === 0 ? e.id : ""), cell(i === 0 ? e.kind : ""),
                cell(i === 0 ? (e.refundId ? `${e.paymentId} / ${e.refundId}` : e.paymentId) : "", "mono"),
                cell(i === 0 ? e.effectiveAt.replace("T", " ").replace("Z", "") : ""),
                cell(p.account, "mono"),
                cell(p.debit ? money(p.debit, p.currency) : "", "num"),
                cell(p.credit ? money(p.credit, p.currency) : "", "num"),
            );
            if (i === 0) tr.className = "entry-start";
            rows.push(tr);
        });
    }
    fill("#journal-table", rows, "no entries yet", 7);
}

function showRefundable() {
    const o = $("#refund-payment").selectedOptions[0];
    $("#refundable").textContent = o ? `Refundable: ${money(Number(o.dataset.refundable), o.dataset.currency)}` : "No refundable payments.";
}

// ── Actions ───────────────────────────────────────────────────────────────────────────────────────────────

function captureBody(form) {
    const f = new FormData(form);
    const currency = f.get("currency");
    return {
        paymentId: f.get("paymentId").trim() || newId("pay"),
        merchantId: f.get("merchantId").trim(),
        amount: toMinor(f.get("amount"), currency),
        currency,
        taxRate: percentToRate(f.get("taxRate")),
        taxJurisdiction: f.get("taxJurisdiction").trim().toUpperCase(),
        ...(f.get("capturedAt") ? { capturedAt: isoOrNull(f.get("capturedAt")) } : {}),
    };
}

async function guarded(fn) {
    try {
        await fn();
    } catch (e) {
        log("UI", "", 0, { error: "client", message: e.message });
    }
    await refresh();
}

$("#capture-form").addEventListener("submit", (ev) => {
    ev.preventDefault();
    guarded(async () => {
        lastCapture = captureBody(ev.target);
        $("#replay").disabled = false;
        await api("POST", "/v1/payments", lastCapture);
    });
});

$("#replay").addEventListener("click", () => guarded(() => api("POST", "/v1/payments", lastCapture)));

$("#refund-form").addEventListener("submit", (ev) => {
    ev.preventDefault();
    guarded(async () => {
        const f = new FormData(ev.target);
        const o = $("#refund-payment").selectedOptions[0];
        if (!o) throw new Error("no payment selected");
        const body = { refundId: newId("ref") };
        if (f.get("amount").trim()) body.amount = toMinor(f.get("amount"), o.dataset.currency);
        if (f.get("refundedAt")) body.refundedAt = isoOrNull(f.get("refundedAt"));
        await api("POST", `/v1/payments/${encodeURIComponent(o.value)}/refunds`, body);
        ev.target.amount.value = "";
    });
});

$("#refund-payment").addEventListener("change", showRefundable);
$("#as-of").addEventListener("change", refresh);

$("#scenario").addEventListener("click", () => guarded(async () => {
    const run = Date.now().toString(36);
    const p1 = { paymentId: `pay_${run}_1`, merchantId: "acme", amount: 12100, currency: "EUR", taxRate: "0.21", taxJurisdiction: "NL" };
    const p2 = { paymentId: `pay_${run}_2`, merchantId: "acme", amount: 11900, currency: "EUR", taxRate: "0.19", taxJurisdiction: "DE" };
    const p3 = { paymentId: `pay_${run}_3`, merchantId: "globex", amount: 5000, currency: "USD", taxRate: "0.0725", taxJurisdiction: "US-CA" };
    for (const p of [p1, p2, p3]) await api("POST", "/v1/payments", p);
    await api("POST", `/v1/payments/${p1.paymentId}/refunds`, { refundId: `ref_${run}_1a`, amount: 6050 }); // half
    await api("POST", `/v1/payments/${p3.paymentId}/refunds`, { refundId: `ref_${run}_3` });                // full
    await api("POST", "/v1/payments", p2);                                                                  // replay -> 200
    await api("POST", "/v1/payments", { ...p2, amount: 999 });                                              // conflict -> 409
    await api("POST", `/v1/payments/${p1.paymentId}/refunds`, { refundId: `ref_${run}_1b`, amount: 999999 }); // too much -> 422
}));

async function health() {
    const pill = $("#health");
    try {
        const res = await fetch("/health");
        pill.textContent = res.ok ? "API up" : "API error";
        pill.className = "pill " + (res.ok ? "ok" : "bad");
    } catch {
        pill.textContent = "API down";
        pill.className = "pill bad";
    }
}

health();
refresh();
