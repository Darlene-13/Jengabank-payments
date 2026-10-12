-- V3 — ledger invariants + tamper-evident audit chain.

-- (1) Every transfer's ledger entries must net to zero. Checked at COMMIT so a
--     transfer may legally be journaled across several statements inside one tx.
CREATE OR REPLACE FUNCTION ledger_transfer_balance_check() RETURNS trigger AS $$
DECLARE
    bad BIGINT;
BEGIN
    SELECT COUNT(*) INTO bad FROM (
        SELECT transfer_id
        FROM ledger_entries
        WHERE transfer_id IS NOT NULL
        GROUP BY transfer_id
        HAVING COALESCE(SUM(amount_minor), 0) <> 0
    ) unbalanced;

    IF bad > 0 THEN
        RAISE EXCEPTION 'ledger imbalance: % transfer(s) with non-zero net entries', bad;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_transfer_balance_trigger
    AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transfer_balance_check();

-- (2) No customer may ever be overdrawn; checked at COMMIT as well, so the
--     invariant holds no matter what the Java code does.
CREATE OR REPLACE FUNCTION customer_balance_nonnegative_check() RETURNS trigger AS $$
DECLARE
    bad BIGINT;
BEGIN
    SELECT COUNT(*) INTO bad
    FROM account_balances
    WHERE kind = 'CUSTOMER' AND balance_minor < 0;

    IF bad > 0 THEN
        RAISE EXCEPTION 'overdrawn customer account(s) detected';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER customer_balance_nonnegative_trigger
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION customer_balance_nonnegative_check();

-- (3) Hash-chained audit trail. Application code only INSERTs
--     (actor, action, target, details); sequence, timestamp, prev_hash and hash
--     are computed here under an advisory lock so concurrent writers cannot fork
--     the chain and any historical edit invalidates every later hash.
CREATE OR REPLACE FUNCTION audit_chain() RETURNS trigger AS $$
DECLARE
    v_lock      CONSTANT BIGINT  := 900116;
    v_prev_hash TEXT;
    v_seq       BIGINT;
    v_ts        TIMESTAMPTZ     := clock_timestamp();
    v_payload   TEXT;
BEGIN
    PERFORM pg_advisory_xact_lock(v_lock);

    SELECT seq, hash INTO v_seq, v_prev_hash
    FROM audit_log
    ORDER BY seq DESC
    LIMIT 1;

    v_prev_hash := COALESCE(v_prev_hash, LPAD('', 64, '0'));
    v_seq       := COALESCE(v_seq, 0) + 1;

    NEW.seq        := v_seq;
    NEW.created_at := v_ts;
    NEW.prev_hash  := v_prev_hash;

    v_payload := v_seq::text || '|' || v_ts::text || '|' || NEW.actor || '|' || NEW.action
                 || '|' || COALESCE(NEW.target, '') || '|' || NEW.details::text || '|' || v_prev_hash;

    NEW.hash := encode(sha256(convert_to(v_payload, 'UTF8')), 'hex');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_chain_trigger
    BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_chain();
