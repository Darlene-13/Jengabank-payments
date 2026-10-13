-- =====================================================================
-- V2 — append-only enforcement at the database layer.
-- Even a compromised application account cannot rewrite history.
--
-- Aligned with V1 as it exists after Dev 2's schema rewrite (c74df35):
--   - No journals table
--   - No reversals table
--   - transfers uses source_account_id / target_account_id (not from_/to_)
--   - No journal_id column on transfers
--   - transfers.status CHECK allows only PENDING_APPROVAL, COMPLETED, BLOCKED, REJECTED
--
-- NOTE (Dev 3): db/migration/ is the Build Lead's area. This change aligns V2
-- with the current V1 so tests can run. Please review before merge.
-- =====================================================================

CREATE FUNCTION forbid_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only table "%": % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- STATEMENT-level: fires even when the UPDATE/DELETE matches zero rows,
-- so tests that assert immutability pass on an empty table.
CREATE TRIGGER entries_append_only  BEFORE UPDATE OR DELETE ON ledger_entries      FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER audit_append_only    BEFORE UPDATE OR DELETE ON audit_log           FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER recon_append_only    BEFORE UPDATE OR DELETE ON reconciliation_runs FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

CREATE TRIGGER entries_no_truncate    BEFORE TRUNCATE ON ledger_entries      FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER audit_no_truncate      BEFORE TRUNCATE ON audit_log           FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER recon_no_truncate      BEFORE TRUNCATE ON reconciliation_runs FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER transfers_no_truncate  BEFORE TRUNCATE ON transfers           FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

-- Transfers: never deleted; core fields immutable; status only forward.
CREATE FUNCTION guard_transfer_update() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'transfers cannot be deleted' USING ERRCODE = 'insufficient_privilege';
END IF;

    IF (NEW.id, NEW.source_account_id, NEW.target_account_id, NEW.amount_minor, NEW.currency,
        NEW.reference, NEW.created_by, NEW.created_at, NEW.fraud_decision, NEW.fraud_reasons)
       IS DISTINCT FROM
       (OLD.id, OLD.source_account_id, OLD.target_account_id, OLD.amount_minor, OLD.currency,
        OLD.reference, OLD.created_by, OLD.created_at, OLD.fraud_decision, OLD.fraud_reasons) THEN
        RAISE EXCEPTION 'transfer % : core fields are immutable', OLD.id
            USING ERRCODE = 'insufficient_privilege';
END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           OLD.status = 'PENDING_APPROVAL'
           AND NEW.status IN ('COMPLETED', 'REJECTED')
    ) THEN
        RAISE EXCEPTION 'transfer % : illegal status change % -> %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
END IF;
RETURN NEW;
END;
$$;

CREATE TRIGGER transfers_guard BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION guard_transfer_update();