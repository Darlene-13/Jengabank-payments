-- =====================================================================
-- V4: made-up seed data. Passwords are shared in the team chat only.
-- Opening balances are real double-entry journals from the bank's
-- funding account, never a typed-in balance.
-- =====================================================================

INSERT INTO users (id, username, display_name, password_hash, role) VALUES
 ('00000000-0000-4000-8000-000000000001', 'wanjiku',      'Wanjiku Kamau', '$2b$12$0yUwNwbQSDJ4fc2mTElixuAYu0sPh6mrZjCPjDdNOfgcMjQu/9O6u', 'CUSTOMER'),
 ('00000000-0000-4000-8000-000000000002', 'otieno',       'Brian Otieno',  '$2b$12$ZRcANOK55JifJu56RX04XuKLMMxcfgnaosDZBDqvn189WkKnVOnTy', 'CUSTOMER'),
 ('00000000-0000-4000-8000-000000000003', 'ops.maker',    'Grace Njeri',   '$2b$12$7n6tChs7bhOU/5BomZG8reTI2G/R/X/gIfxR0PJBK0rVgfCvUtd4u', 'MAKER'),
 ('00000000-0000-4000-8000-000000000004', 'ops.checker',  'David Mwangi',  '$2b$12$WBW2x8XgDdJcqIvS3L4oWu416U5tq3AitN4mqaIAW.dNnwLsbKHNm', 'CHECKER'),
 ('00000000-0000-4000-8000-000000000005', 'ops.checker2', 'Aisha Hassan',  '$2b$12$ja3GhKEHlsOqdp3a6vAry.DJ1RDLnzdp4T30T7na0m4wd7BiWb7Sa', 'CHECKER');

INSERT INTO accounts (id, account_number, owner_id, name, type, allow_negative) VALUES
 ('10000000-0000-4000-8000-000000000000', '000000000001', NULL, 'JengaBank funding', 'INTERNAL', TRUE),
 ('10000000-0000-4000-8000-000000000001', '011022384410', '00000000-0000-4000-8000-000000000001', 'Everyday account', 'CUSTOMER', FALSE),
 ('10000000-0000-4000-8000-000000000002', '011022389902', '00000000-0000-4000-8000-000000000001', 'Savings',          'CUSTOMER', FALSE),
 ('10000000-0000-4000-8000-000000000003', '011077120035', '00000000-0000-4000-8000-000000000002', 'Everyday account', 'CUSTOMER', FALSE);

-- Opening balances: KES 184,250.50 / 2,500,000.00 / 96,000.00
INSERT INTO journals (id, kind, description) VALUES
 ('20000000-0000-4000-8000-000000000001', 'OPENING', 'Opening balance'),
 ('20000000-0000-4000-8000-000000000002', 'OPENING', 'Opening balance'),
 ('20000000-0000-4000-8000-000000000003', 'OPENING', 'Opening balance');

INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES
 ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000000', 'DEBIT',   18425050),
 ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'CREDIT',  18425050),
 ('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000000', 'DEBIT',  250000000),
 ('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', 'CREDIT', 250000000),
 ('20000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000000', 'DEBIT',    9600000),
 ('20000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000003', 'CREDIT',   9600000);

INSERT INTO audit_log (actor, action, target, details) VALUES
 ('system', 'seed.loaded', 'database', '{"migration": "V4"}');
