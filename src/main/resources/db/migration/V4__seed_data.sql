-- =====================================================================
-- V4 — made-up seed data. Aligned with V1 as it exists after Dev 2's
-- schema rewrite (c74df35).
--
-- NOTE (Dev 3): db/migration/ is the Build Lead's area. This change is
-- only to unblock testing after the schema rewrite. Please review before merge.
-- =====================================================================

-- Bank funding account plus three customer accounts.
INSERT INTO accounts (account_no, owner_id, owner_name, kind, status) VALUES
                                                                          ('9000',         'system',  'JengaBank funding', 'BANK',     'ACTIVE'),
                                                                          ('011022384410', 'wanjiku', 'Wanjiku Kamau',     'CUSTOMER', 'ACTIVE'),
                                                                          ('011022389902', 'wanjiku', 'Wanjiku Kamau',     'CUSTOMER', 'ACTIVE'),
                                                                          ('011077120035', 'otieno',  'Brian Otieno',      'CUSTOMER', 'ACTIVE');

-- Opening balances as signed double-entry lines.
-- NULL is explicitly cast to bigint so the UNION ALL branches share the correct type.
INSERT INTO ledger_entries (transfer_id, entry_type, account_id, amount_minor, currency, memo)
SELECT NULL::bigint, 'OPENING', id, -18425050,  'KES', 'opening debit'
FROM accounts WHERE account_no = '9000'
UNION ALL
SELECT NULL::bigint, 'OPENING', id,  18425050,  'KES', 'opening credit'
FROM accounts WHERE account_no = '011022384410'
UNION ALL
SELECT NULL::bigint, 'OPENING', id, -250000000, 'KES', 'opening debit'
FROM accounts WHERE account_no = '9000'
UNION ALL
SELECT NULL::bigint, 'OPENING', id,  250000000, 'KES', 'opening credit'
FROM accounts WHERE account_no = '011022389902'
UNION ALL
SELECT NULL::bigint, 'OPENING', id, -9600000,   'KES', 'opening debit'
FROM accounts WHERE account_no = '9000'
UNION ALL
SELECT NULL::bigint, 'OPENING', id,  9600000,   'KES', 'opening credit'
FROM accounts WHERE account_no = '011077120035';

-- One seed audit row. The V3 audit_chain_trigger fills seq, created_at, prev_hash, hash.
INSERT INTO audit_log (actor, action, target, details) VALUES
    ('system', 'seed.loaded', 'database', '{"migration": "V4"}'::jsonb);