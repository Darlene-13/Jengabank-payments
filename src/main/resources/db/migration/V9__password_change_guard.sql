-- =====================================================================
-- V9: refuse any statement that changes more than one password at once.
-- The app only ever changes a single user's hash, so a bulk rewrite
-- (e.g. every account set to one shared password) can only come from
-- direct DB access and is rejected. Defence in depth: the DB role that
-- owns the table can still drop the trigger, which is itself auditable.
-- =====================================================================

CREATE OR REPLACE FUNCTION forbid_bulk_password_change() RETURNS trigger AS $$
BEGIN
    IF (SELECT count(*) FROM old_rows o JOIN new_rows n ON n.id = o.id
         WHERE n.password_hash IS DISTINCT FROM o.password_hash) > 1 THEN
        RAISE EXCEPTION 'Bulk password modification forbidden: change one user per statement';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Postgres doesn't allow a column list with transition tables, so this
-- fires on every users UPDATE and compares hashes itself.
CREATE TRIGGER users_password_bulk_guard
    AFTER UPDATE ON users
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_bulk_password_change();
