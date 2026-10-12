-- =====================================================================
-- V3: invariants checked by the database itself at COMMIT.
--   1. Every journal balances (debits = credits, at least 2 lines).
--   2. No customer account ever goes below zero.
--   3. Audit records are hash-chained, in strict order.
-- These are the last line of defence if app-level locking ever fails.
-- =====================================================================

-- 1. Balanced journals --------------------------------------------------
CREATE FUNCTION check_journal_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    debits  BIGINT;
    credits BIGINT;
    lines   INT;
BEGIN
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
      INTO debits, credits, lines
      FROM ledger_entries
     WHERE journal_id = NEW.journal_id;

    IF lines < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'journal % does not balance: debits %, credits %, lines %',
            NEW.journal_id, debits, credits, lines
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER entries_journal_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

-- 2. No overdrafts ------------------------------------------------------
CREATE FUNCTION check_no_overdraft() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    bal       BIGINT;
    may_go_negative BOOLEAN;
BEGIN
    -- Row lock: a concurrent transaction debiting the same account waits here until
    -- we commit, then re-reads the balance including our entries. No double spend.
    SELECT allow_negative INTO may_go_negative FROM accounts WHERE id = NEW.account_id FOR NO KEY UPDATE;
    IF may_go_negative THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
      INTO bal
      FROM ledger_entries
     WHERE account_id = NEW.account_id;

    IF bal < 0 THEN
        RAISE EXCEPTION 'account % would go negative (%)', NEW.account_id, bal
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER entries_no_overdraft
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.direction = 'DEBIT')
    EXECUTE FUNCTION check_no_overdraft();

-- 3. Audit hash chain ---------------------------------------------------
-- The app only INSERTs (actor, action, target, details). The database
-- assigns sequence, prev_hash and hash under a lock, so concurrent writers
-- can never fork or reorder the chain.
CREATE FUNCTION audit_hash_input(seq BIGINT, ts TIMESTAMPTZ, actor TEXT, action TEXT,
                                 target TEXT, details JSONB, prev TEXT)
RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT prev || '|' || seq::text || '|'
        || to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|'
        || actor || '|' || action || '|' || target || '|' || details::text;
$$;

CREATE FUNCTION audit_chain() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    last_hash TEXT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('jengabank.audit_log'));
    SELECT hash INTO last_hash FROM audit_log ORDER BY sequence DESC LIMIT 1;

    NEW.sequence  := nextval('audit_log_seq');
    NEW.ts        := clock_timestamp();
    NEW.prev_hash := COALESCE(last_hash, repeat('0', 64));
    NEW.hash      := encode(sha256(convert_to(
                        audit_hash_input(NEW.sequence, NEW.ts, NEW.actor, NEW.action,
                                         NEW.target, NEW.details, NEW.prev_hash), 'UTF8')), 'hex');
    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_log_chain BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_chain();

-- Walks the whole chain. Returns valid = false and the first bad sequence if tampered.
CREATE FUNCTION audit_verify(OUT valid BOOLEAN, OUT checked BIGINT, OUT broken_at BIGINT)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    r        RECORD;
    expected TEXT := repeat('0', 64);
BEGIN
    valid := TRUE; checked := 0; broken_at := NULL;
    FOR r IN SELECT * FROM audit_log ORDER BY sequence LOOP
        checked := checked + 1;
        IF r.prev_hash <> expected
           OR r.hash <> encode(sha256(convert_to(
                  audit_hash_input(r.sequence, r.ts, r.actor, r.action, r.target, r.details, r.prev_hash),
                  'UTF8')), 'hex') THEN
            valid := FALSE; broken_at := r.sequence;
            RETURN;
        END IF;
        expected := r.hash;
    END LOOP;
END;
$$;
