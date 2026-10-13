-- Ledger invariants (security-test-plan.md, Appendix A). Every query returns 0 rows,
-- except query 2, which must return 0. Read-only; safe on the live database.
-- Run: docker exec -i <db-container> psql -U jengabank -d jengabank_payments -f - < docs/ledger-invariants.sql

-- 1. Every journal balances (debits = credits)
SELECT journal_id,
       SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net
FROM ledger_entries
GROUP BY journal_id
HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0;

-- 2. System-wide net is zero (expect 0)
SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0) AS system_net
FROM ledger_entries;

-- 3. Balances are derived, never stored: account_balances must equal the sum of entries
SELECT b.account_id
FROM account_balances b
LEFT JOIN (SELECT account_id,
                  SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END) AS s
           FROM ledger_entries GROUP BY account_id) e ON e.account_id = b.account_id
WHERE b.balance_minor <> COALESCE(e.s, 0);

-- 4. No negative balance except the bank's funding account (allow_negative)
SELECT a.id, a.account_number, b.balance_minor
FROM accounts a
JOIN account_balances b ON b.account_id = a.id
WHERE b.balance_minor < 0 AND NOT a.allow_negative;

-- 5. One stored result per idempotency key, scoped to user (primary key enforces it; expect no rows)
SELECT user_id, idem_key, COUNT(*)
FROM idempotency_keys
GROUP BY user_id, idem_key
HAVING COUNT(*) > 1;

-- 6. At most one live reversal per transfer
SELECT transfer_id, COUNT(*)
FROM reversals
WHERE status <> 'REJECTED'
GROUP BY transfer_id
HAVING COUNT(*) > 1;

-- 7. Each journal reversed at most once, and never a reversal of a reversal
SELECT r.id
FROM journals r
JOIN journals o ON o.id = r.reversal_of
WHERE r.kind = 'REVERSAL' AND o.kind = 'REVERSAL';

-- 8. Transfer status matches the ledger: COMPLETED/REVERSED have a journal; REVERSED has a reversal journal
SELECT id, status
FROM transfers
WHERE (status IN ('COMPLETED', 'REVERSED') AND journal_id IS NULL)
   OR (status = 'REVERSED' AND reversal_journal_id IS NULL)
   OR (status IN ('PENDING_APPROVAL', 'REJECTED', 'BLOCKED') AND journal_id IS NOT NULL);

-- 9. Each posted transfer's journal moves exactly its amount from sender to receiver
SELECT t.id
FROM transfers t
JOIN ledger_entries d ON d.journal_id = t.journal_id AND d.direction = 'DEBIT'
JOIN ledger_entries c ON c.journal_id = t.journal_id AND c.direction = 'CREDIT'
WHERE t.journal_id IS NOT NULL
  AND (d.account_id <> t.from_account_id OR c.account_id <> t.to_account_id
       OR d.amount_minor <> t.amount_minor OR c.amount_minor <> t.amount_minor);

-- 10. Maker-checker held: nobody decided their own transfer or reversal
SELECT 'transfer' AS kind, id FROM transfers WHERE decided_by = created_by
UNION ALL
SELECT 'reversal', id FROM reversals WHERE decided_by = requested_by;
