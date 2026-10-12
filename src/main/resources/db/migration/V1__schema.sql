-- =====================================================================
-- JengaBank Payments (APP04) - V1 schema
-- Money is BIGINT cents. There is NO balance column anywhere:
-- a balance is always SUM(ledger_entries) for the account.
-- =====================================================================

-- ---------- Users and sessions ----------------------------------------
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username            VARCHAR(64)  NOT NULL UNIQUE
                        CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    display_name        VARCHAR(120) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,          -- BCrypt only
    role                VARCHAR(16)  NOT NULL
                        CHECK (role IN ('CUSTOMER', 'MAKER', 'CHECKER')),
    failed_login_count  INT          NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per issued token (id = token's jti). Lets logout really revoke.
CREATE TABLE sessions (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_sessions_user ON sessions (user_id);

-- ---------- Accounts ----------------------------------------------------
CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number  VARCHAR(20) NOT NULL UNIQUE CHECK (account_number ~ '^[0-9]{6,20}$'),
    owner_id        UUID REFERENCES users (id),            -- NULL for the bank's own accounts
    name            VARCHAR(80) NOT NULL,
    type            VARCHAR(16) NOT NULL CHECK (type IN ('CUSTOMER', 'INTERNAL')),
    currency        CHAR(3)     NOT NULL DEFAULT 'KES' CHECK (currency = 'KES'),
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    allow_negative  BOOLEAN     NOT NULL DEFAULT FALSE,    -- only the bank's funding account
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((type = 'CUSTOMER') = (owner_id IS NOT NULL)),
    CHECK (type = 'INTERNAL' OR allow_negative = FALSE)
);
CREATE INDEX idx_accounts_owner ON accounts (owner_id);

-- ---------- Double-entry ledger ------------------------------------------
-- A journal is one business event. Its entries must balance (checked in V3).
CREATE TABLE journals (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         VARCHAR(16)  NOT NULL CHECK (kind IN ('OPENING', 'TRANSFER', 'REVERSAL')),
    description  VARCHAR(140) NOT NULL,
    reversal_of  UUID UNIQUE REFERENCES journals (id),     -- UNIQUE: reversed once, ever
    created_by   UUID REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((kind = 'REVERSAL') = (reversal_of IS NOT NULL))
);

-- Customer accounts are liabilities: CREDIT adds to the balance, DEBIT takes from it.
CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_id    UUID        NOT NULL REFERENCES journals (id),
    account_id    UUID        NOT NULL REFERENCES accounts (id),
    direction     VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor  BIGINT      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 100000000000),
    currency      CHAR(3)     NOT NULL DEFAULT 'KES' CHECK (currency = 'KES'),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries (account_id, created_at DESC, id DESC);
CREATE INDEX idx_entries_journal ON ledger_entries (journal_id);

-- The only way the app reads a balance.
CREATE VIEW account_balances AS
SELECT a.id AS account_id,
       COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)::BIGINT
           AS balance_minor
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id;

-- ---------- Transfers ------------------------------------------------------
CREATE TABLE transfers (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_account_id      UUID         NOT NULL REFERENCES accounts (id),
    to_account_id        UUID         NOT NULL REFERENCES accounts (id),
    amount_minor         BIGINT       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 100000000000),
    currency             CHAR(3)      NOT NULL DEFAULT 'KES' CHECK (currency = 'KES'),
    reference            VARCHAR(140) NOT NULL DEFAULT '',
    status               VARCHAR(20)  NOT NULL
                         CHECK (status IN ('COMPLETED', 'PENDING_APPROVAL', 'REJECTED', 'BLOCKED', 'REVERSED')),
    fraud_decision       VARCHAR(6)   CHECK (fraud_decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    fraud_reasons        JSONB        NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(fraud_reasons) = 'array'),
    journal_id           UUID UNIQUE REFERENCES journals (id),
    reversal_journal_id  UUID UNIQUE REFERENCES journals (id),
    created_by           UUID         NOT NULL REFERENCES users (id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_by           UUID REFERENCES users (id),       -- the checker
    decided_at           TIMESTAMPTZ,
    rejection_reason     VARCHAR(280),
    CHECK (from_account_id <> to_account_id),
    CHECK (decided_by IS NULL OR decided_by <> created_by),  -- maker-checker, enforced by the DB
    CHECK ((status IN ('COMPLETED', 'REVERSED')) = (journal_id IS NOT NULL)),
    CHECK ((status = 'REVERSED') = (reversal_journal_id IS NOT NULL)),
    CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CHECK ((decided_by IS NULL) = (decided_at IS NULL))
);
CREATE INDEX idx_transfers_from    ON transfers (from_account_id, created_at DESC, id DESC);
CREATE INDEX idx_transfers_to      ON transfers (to_account_id, created_at DESC, id DESC);
CREATE INDEX idx_transfers_status  ON transfers (status, created_at DESC, id DESC);
CREATE INDEX idx_transfers_creator ON transfers (created_by, created_at DESC);  -- velocity + daily limits

-- Reversal requests. Small ones post straight away; large ones wait for a checker.
CREATE TABLE reversals (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id   UUID         NOT NULL REFERENCES transfers (id),
    reason        VARCHAR(280) NOT NULL CHECK (length(reason) >= 10),
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'POSTED', 'REJECTED')),
    journal_id    UUID UNIQUE REFERENCES journals (id),
    requested_by  UUID         NOT NULL REFERENCES users (id),
    requested_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_by    UUID REFERENCES users (id),
    decided_at    TIMESTAMPTZ,
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'POSTED') = (journal_id IS NOT NULL))
);
-- At most one live (pending or posted) reversal per transfer.
CREATE UNIQUE INDEX uq_reversal_live ON reversals (transfer_id) WHERE status <> 'REJECTED';

-- ---------- Idempotency ---------------------------------------------------
-- Same (user, key) + same request_hash  -> replay stored response.
-- Same (user, key) + different hash     -> 409.
CREATE TABLE idempotency_keys (
    user_id        UUID         NOT NULL REFERENCES users (id),
    idem_key       UUID         NOT NULL,
    endpoint       VARCHAR(120) NOT NULL,
    request_hash   CHAR(64)     NOT NULL,                  -- SHA-256 hex of method + path + body
    status_code    INT,
    response_body  JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);

-- ---------- Audit (hash chain filled in by trigger in V3) ------------------
CREATE SEQUENCE audit_log_seq;
CREATE TABLE audit_log (
    sequence   BIGINT       PRIMARY KEY,
    ts         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor      VARCHAR(64)  NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    target     VARCHAR(200) NOT NULL,
    details    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    prev_hash  CHAR(64)     NOT NULL,
    hash       CHAR(64)     NOT NULL UNIQUE
);
CREATE INDEX idx_audit_ts ON audit_log (ts DESC);

-- ---------- Reconciliation -------------------------------------------------
CREATE TABLE reconciliation_runs (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    status  VARCHAR(6)  NOT NULL CHECK (status IN ('PASSED', 'FAILED')),
    checks  JSONB       NOT NULL CHECK (jsonb_typeof(checks) = 'array'),
    run_by  UUID REFERENCES users (id)                     -- NULL = scheduled job
);
CREATE INDEX idx_recon_run_at ON reconciliation_runs (run_at DESC);
