-- V4 — demo data. Opening balances are themselves double-entry lines against the
-- bank equity account, so SUM(ledger_entries) = 0 from the very first migration.

INSERT INTO accounts (account_no, owner_id, owner_name, kind) VALUES
    ('1001', 'alice', 'Alice Otieno',        'CUSTOMER'),
    ('1002', 'bob',   'Bob Kamau',           'CUSTOMER'),
    ('1003', 'carol', 'Carol Wanjiku',       'CUSTOMER'),
    ('9000', 'bank',  'JengaBank Equity',    'BANK');

INSERT INTO ledger_entries (transfer_id, entry_type, account_id, amount_minor, currency, memo)
SELECT NULL, 'OPENING', a.id, v.amount_minor, 'KES', 'opening balance'
FROM (VALUES
        ('1001',  50000000::BIGINT),
        ('1002',  50000000::BIGINT),
        ('1003',  20000000::BIGINT),
        ('9000', -120000000::BIGINT)
     ) AS v(account_no, amount_minor)
JOIN accounts a ON a.account_no = v.account_no;
