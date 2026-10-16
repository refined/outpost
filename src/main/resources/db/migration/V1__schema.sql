-- Double-entry ledger (schema `ledger`) and the business records it is posted from (schema `public`).
--
-- Invariants enforced by the database, not only by the code:
--   * every journal entry balances (sum of signed postings = 0) and uses a single currency — checked at commit;
--   * history is append-only: UPDATE and DELETE on entries, postings, payments and refunds are rejected;
--   * an account's identity never changes; only its running `balance` is updated, in the same transaction as
--     the postings that move it.
--   (TRUNCATE is not blocked; it is an admin operation, used by tests to reset state.)

CREATE SCHEMA ledger;

-- ─── Ledger ─────────────────────────────────────────────────────────────────────────────────────────────

CREATE TABLE ledger.accounts (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT    NOT NULL,                 -- e.g. merchant_payable:m_123
    currency    CHAR(3) NOT NULL,                 -- ISO 4217; one account per (code, currency)
    normal_side TEXT    NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    balance     BIGINT  NOT NULL DEFAULT 0,       -- running Σ of this account's postings (debit > 0)
    UNIQUE (code, currency)
);

CREATE TABLE ledger.journal_entries (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT        NOT NULL CHECK (kind IN ('CAPTURE', 'REFUND')),
    payment_id   TEXT        NOT NULL,
    refund_id    TEXT,
    effective_at TIMESTAMPTZ NOT NULL,             -- when it happened (event time); reports filter on this
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now() -- when we learned about it
);
CREATE INDEX journal_entries_payment_idx ON ledger.journal_entries (payment_id);
CREATE INDEX journal_entries_effective_idx ON ledger.journal_entries (effective_at);

-- Signed amounts in minor units: debit > 0, credit < 0.
CREATE TABLE ledger.postings (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT NOT NULL REFERENCES ledger.journal_entries (id),
    account_id BIGINT NOT NULL REFERENCES ledger.accounts (id),
    amount     BIGINT NOT NULL CHECK (amount <> 0)
);
CREATE INDEX postings_entry_idx ON ledger.postings (entry_id);
CREATE INDEX postings_account_idx ON ledger.postings (account_id);

CREATE FUNCTION ledger.check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    entry      BIGINT;
    total      NUMERIC;
    lines      INT;
    currencies INT;
BEGIN
    IF TG_TABLE_NAME = 'postings' THEN
        entry := NEW.entry_id;
    ELSE
        entry := NEW.id;
    END IF;

    SELECT COALESCE(SUM(p.amount), 0), COUNT(*), COUNT(DISTINCT a.currency)
    INTO total, lines, currencies
    FROM ledger.postings p
             JOIN ledger.accounts a ON a.id = p.account_id
    WHERE p.entry_id = entry;

    IF lines < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s), needs at least 2', entry, lines USING ERRCODE = 'check_violation';
    END IF;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: postings sum to %', entry, total USING ERRCODE = 'check_violation';
    END IF;
    IF currencies <> 1 THEN
        RAISE EXCEPTION 'journal entry % mixes % currencies', entry, currencies USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

-- Deferred to commit time, so an entry and its postings can be inserted in any order within one transaction.
CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON ledger.journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON ledger.postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

CREATE FUNCTION ledger.forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END
$$;

-- Accounts: the running balance is the only mutable column.
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

-- Accounts of the TigerBeetle engine (LEDGER_ENGINE=TIGERBEETLE). TigerBeetle keeps balances under 128-bit ids;
-- this maps them back to account codes so that reports can list them. Unused with the Postgres engine.
CREATE TABLE ledger.tb_accounts (
    id          NUMERIC(39) PRIMARY KEY,          -- the TigerBeetle account id (u128)
    code        TEXT    NOT NULL,
    currency    CHAR(3) NOT NULL,
    normal_side TEXT    NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    UNIQUE (code, currency)
);

-- ─── Business records ───────────────────────────────────────────────────────────────────────────────────

-- A captured payment with the split and the terms (rates, refund policy) snapshotted at capture time.
CREATE TABLE payments (
    payment_id        TEXT PRIMARY KEY,           -- external id (PSP / checkout): the idempotency key
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
    fingerprint       TEXT          NOT NULL,     -- hash of the request, to tell a replay from a conflict
    CHECK (tax_amount + fee_amount + merchant_amount = amount)
);
CREATE INDEX payments_merchant_time_idx ON payments (merchant_id, captured_at);
CREATE INDEX payments_time_idx ON payments (captured_at);

CREATE TABLE refunds (
    refund_id       TEXT PRIMARY KEY,             -- external id: the idempotency key
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
