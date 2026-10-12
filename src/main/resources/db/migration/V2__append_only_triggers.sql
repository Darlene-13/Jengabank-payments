-- =====================================================================
-- V2: append-only enforcement. Even a bug or SQL injection in the app
-- cannot edit or delete money records or the audit trail.
-- =====================================================================

CREATE FUNCTION forbid_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only table "%": % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER journals_append_only       BEFORE UPDATE OR DELETE ON journals            FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER entries_append_only        BEFORE UPDATE OR DELETE ON ledger_entries      FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER audit_append_only          BEFORE UPDATE OR DELETE ON audit_log           FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER recon_append_only          BEFORE UPDATE OR DELETE ON reconciliation_runs FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TRIGGER journals_no_truncate       BEFORE TRUNCATE ON journals            FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER entries_no_truncate        BEFORE TRUNCATE ON ledger_entries      FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER audit_no_truncate          BEFORE TRUNCATE ON audit_log           FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER recon_no_truncate          BEFORE TRUNCATE ON reconciliation_runs FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER transfers_no_truncate      BEFORE TRUNCATE ON transfers           FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER reversals_no_truncate      BEFORE TRUNCATE ON reversals           FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

-- Transfers: never deleted; only status/decision columns move, and only forward.
CREATE FUNCTION guard_transfer_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'transfers cannot be deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF (NEW.id, NEW.from_account_id, NEW.to_account_id, NEW.amount_minor, NEW.currency,
        NEW.reference, NEW.created_by, NEW.created_at, NEW.fraud_decision, NEW.fraud_reasons)
       IS DISTINCT FROM
       (OLD.id, OLD.from_account_id, OLD.to_account_id, OLD.amount_minor, OLD.currency,
        OLD.reference, OLD.created_by, OLD.created_at, OLD.fraud_decision, OLD.fraud_reasons) THEN
        RAISE EXCEPTION 'transfer % : core fields are immutable', OLD.id USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF OLD.journal_id IS NOT NULL AND NEW.journal_id IS DISTINCT FROM OLD.journal_id THEN
        RAISE EXCEPTION 'transfer % : journal cannot change once posted', OLD.id USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('COMPLETED', 'REJECTED'))
        OR (OLD.status = 'COMPLETED'        AND NEW.status = 'REVERSED')
    ) THEN
        RAISE EXCEPTION 'transfer % : illegal status change % -> %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transfers_guard BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION guard_transfer_update();

-- Reversals: never deleted; only PENDING_APPROVAL may be decided.
CREATE FUNCTION guard_reversal_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'reversals cannot be deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (NEW.id, NEW.transfer_id, NEW.reason, NEW.requested_by, NEW.requested_at)
       IS DISTINCT FROM (OLD.id, OLD.transfer_id, OLD.reason, OLD.requested_by, OLD.requested_at) THEN
        RAISE EXCEPTION 'reversal % : core fields are immutable', OLD.id USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND OLD.status <> 'PENDING_APPROVAL' THEN
        RAISE EXCEPTION 'reversal % : already %', OLD.id, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER reversals_guard BEFORE UPDATE OR DELETE ON reversals
    FOR EACH ROW EXECUTE FUNCTION guard_reversal_update();
