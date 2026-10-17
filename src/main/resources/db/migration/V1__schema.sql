-- Invariants enforced here, not only in code: entries, postings, payments and refunds are append-only; an account
-- can change only its running balance. That every entry balances in one currency is enforced in code
-- (Ledger.validated) and detected by /v1/ledger/verify.
-- TRUNCATE is not blocked: tests use it to reset state.

CREATE SCHEMA ledger;

CREATE TABLE ledger.accounts (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT    NOT NULL,
    currency    CHAR(3) NOT NULL,
    normal_side TEXT    NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    balance     BIGINT  NOT NULL DEFAULT 0,       -- running sum of the postings, debit > 0
    UNIQUE (code, currency)
);

CREATE TABLE ledger.journal_entries (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT        NOT NULL CHECK (kind IN ('CAPTURE', 'REFUND')),
    payment_id   TEXT        NOT NULL,
    refund_id    TEXT,
    effective_at TIMESTAMPTZ NOT NULL,             -- event time; asOf reports filter on it
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX journal_entries_payment_idx ON ledger.journal_entries (payment_id);
CREATE INDEX journal_entries_effective_idx ON ledger.journal_entries (effective_at);

CREATE TABLE ledger.postings (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT NOT NULL REFERENCES ledger.journal_entries (id),
    account_id BIGINT NOT NULL REFERENCES ledger.accounts (id),
    amount     BIGINT NOT NULL CHECK (amount <> 0)
);
CREATE INDEX postings_entry_idx ON ledger.postings (entry_id);
CREATE INDEX postings_account_idx ON ledger.postings (account_id);

CREATE FUNCTION ledger.forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE FUNCTION ledger.only_balance_changes() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (NEW.id, NEW.code, NEW.currency, NEW.normal_side) IS DISTINCT FROM (OLD.id, OLD.code, OLD.currency, OLD.normal_side) THEN
        RAISE EXCEPTION 'ledger.accounts: only balance may change' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER accounts_only_balance_changes BEFORE UPDATE ON ledger.accounts
    FOR EACH ROW EXECUTE FUNCTION ledger.only_balance_changes();
CREATE TRIGGER accounts_no_delete BEFORE DELETE ON ledger.accounts
    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation();
CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON ledger.journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation();
CREATE TRIGGER postings_append_only BEFORE UPDATE OR DELETE ON ledger.postings
    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation();

CREATE TABLE payments (
    payment_id        TEXT PRIMARY KEY,           -- external event id = idempotency key
    merchant_id       TEXT          NOT NULL,
    currency          CHAR(3)       NOT NULL,
    amount            BIGINT        NOT NULL CHECK (amount > 0),
    tax_amount        BIGINT        NOT NULL CHECK (tax_amount >= 0),
    fee_amount        BIGINT        NOT NULL CHECK (fee_amount >= 0),
    merchant_amount   BIGINT        NOT NULL CHECK (merchant_amount >= 0),
    tax_rate          NUMERIC(9, 6) NOT NULL,
    tax_jurisdiction  TEXT          NOT NULL,
    fee_rate          NUMERIC(9, 6) NOT NULL,
    refund_fee_policy TEXT          NOT NULL CHECK (refund_fee_policy IN ('MOR_KEEPS_FEE', 'MOR_RETURNS_FEE')),
    captured_at       TIMESTAMPTZ   NOT NULL,
    recorded_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    fingerprint       TEXT          NOT NULL,     -- request hash: replay vs conflict
    CHECK (tax_amount + fee_amount + merchant_amount = amount)
);
CREATE INDEX payments_merchant_time_idx ON payments (merchant_id, captured_at);
CREATE INDEX payments_time_idx ON payments (captured_at);

CREATE TABLE refunds (
    refund_id       TEXT PRIMARY KEY,             -- external event id = idempotency key
    payment_id      TEXT        NOT NULL REFERENCES payments (payment_id),
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    tax_amount      BIGINT      NOT NULL CHECK (tax_amount >= 0),
    fee_amount      BIGINT      NOT NULL CHECK (fee_amount >= 0),
    merchant_amount BIGINT      NOT NULL CHECK (merchant_amount >= 0),
    refunded_at     TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    fingerprint     TEXT        NOT NULL,
    CHECK (tax_amount + fee_amount + merchant_amount = amount)
);
CREATE INDEX refunds_payment_idx ON refunds (payment_id);
CREATE INDEX refunds_time_idx ON refunds (refunded_at);

CREATE TRIGGER payments_append_only BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation();
CREATE TRIGGER refunds_append_only BEFORE UPDATE OR DELETE ON refunds
    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation();
