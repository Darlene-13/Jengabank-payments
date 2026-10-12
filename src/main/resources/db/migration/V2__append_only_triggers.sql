-- V2 — append-only enforcement at the database layer.
-- Even a compromised application account cannot rewrite history.

CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only table % prohibits %', TG_TABLE_NAME, TG_OPERATION;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER reconciliation_runs_append_only
    BEFORE UPDATE OR DELETE ON reconciliation_runs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
