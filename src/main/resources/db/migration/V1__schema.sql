-- JengaBank Payments (APP04) — V1 core schema
-- Double-entry ledger: money is only ever moved, never created.

CREATE TABLE accounts (
    id          BIGSERIAL   PRIMARY KEY,
    account_no  TEXT        NOT NULL UNIQUE,
    owner_id    TEXT        NOT NULL,
    owner_name  TEXT        NOT NULL,
    kind        TEXT        NOT NULL DEFAULT 'CUSTOMER'
                CHECK (kind IN ('CUSTOMER', 'BANK')),
    status      TEXT        NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
    id                 BIGSERIAL   PRIMARY KEY,
    reference          TEXT        NOT NULL UNIQUE,
    source_account_id  BIGINT      NOT NULL REFERENCES accounts (id),
    target_account_id  BIGINT      NOT NULL REFERENCES accounts (id),
    source_owner_id    TEXT        NOT NULL,
    target_owner_id    TEXT        NOT NULL,
    amount_minor       BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency           TEXT        NOT NULL DEFAULT 'KES' CHECK (char_length(currency) = 3),
    status             TEXT        NOT NULL
                        CHECK (status IN ('PENDING_APPROVAL', 'COMPLETED', 'BLOCKED', 'REJECTED')),
    fraud_decision     TEXT        NOT NULL
                        CHECK (fraud_decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    fraud_reasons      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_by         TEXT        NOT NULL,
    decided_by         TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at         TIMESTAMPTZ,
    -- Maker-Checker (Four-Eyes): a Maker may never approve their own transfer.
    -- NULL decided_by passes (SQL three-valued logic), equality does not.
    CHECK (decided_by <> created_by),
    CHECK (source_account_id <> target_account_id)
);

CREATE INDEX transfers_source_owner_created_idx ON transfers (source_owner_id, created_at);
CREATE INDEX transfers_status_idx                ON transfers (status);
CREATE INDEX transfers_pair_idx                  ON transfers (source_owner_id, target_owner_id, created_at);

-- Signed double-entry lines: credits > 0, debits < 0, every transfer nets to 0.
CREATE TABLE ledger_entries (
    id            BIGSERIAL   PRIMARY KEY,
    transfer_id   BIGINT      REFERENCES transfers (id),
    entry_type    TEXT        NOT NULL DEFAULT 'TRANSFER'
                  CHECK (entry_type IN ('OPENING', 'TRANSFER', 'MANUAL')),
    account_id    BIGINT      NOT NULL REFERENCES accounts (id),
    amount_minor  BIGINT      NOT NULL,
    currency      TEXT        NOT NULL DEFAULT 'KES',
    memo          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_account_idx  ON ledger_entries (account_id);
CREATE INDEX ledger_entries_transfer_idx ON ledger_entries (transfer_id);

CREATE TABLE idempotency_keys (
    "key"           TEXT        PRIMARY KEY,
    request_hash    TEXT        NOT NULL,
    response_status INT         NOT NULL,
    response_body   TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- seq, created_at, prev_hash and hash are filled in by the audit_chain() trigger (V3),
-- so application code can never write an entry that breaks the chain.
CREATE TABLE audit_log (
    seq         BIGINT      PRIMARY KEY,
    actor       TEXT        NOT NULL,
    action      TEXT        NOT NULL,
    target      TEXT,
    details     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    prev_hash   TEXT,
    hash        TEXT,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE reconciliation_runs (
    id           BIGSERIAL   PRIMARY KEY,
    run_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    status       TEXT        NOT NULL CHECK (status IN ('PASSED', 'FAILED')),
    checks       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    triggered_by TEXT        NOT NULL
);

CREATE VIEW account_balances AS
SELECT a.id,
       a.account_no,
       a.owner_id,
       a.owner_name,
       a.kind,
       a.status,
       COALESCE(SUM(le.amount_minor), 0) AS balance_minor
FROM accounts a
LEFT JOIN ledger_entries le ON le.account_id = a.id
GROUP BY a.id, a.account_no, a.owner_id, a.owner_name, a.kind, a.status;
