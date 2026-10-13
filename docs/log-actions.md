# Log Actions: Shared Security Event Names (APP04)

| Field | Value |
|---|---|
| Owner | Dev 2 (logging) |
| Version | 1.1 |
| Date | 12 Oct 2026 |
| Related | `event-logging-verification-matrix.md`, `security-test-plan.md` |

## 1. Purpose

This is the reference for every security event the application logs. It defines the exact string constants for `Actions.java`, who emits each one, when, and which fields must be present. The verification matrix records the test result for each event; this file does not.

## 2. Log line format

Logs are structured JSON, one object per line, UTC ISO 8601 timestamps, with a request ID that matches the `X-Request-Id` response header.

Required fields: `timestamp`, `event`, `requestId`.

Additional fields include: `level` (`INFO`, `WARN`, `ERROR`), `actor` (user ID or `anonymous`), `role` (`CUSTOMER`, `MAKER`, `CHECKER`, or `none`), `ip`, `method`, `path` (template, no sensitive query params), `status`, `outcome` (`SUCCESS`, `DENIED`, `FAILURE`), and `detail` (event-specific).

Never log: passwords, tokens, signing keys, password hashes, full account numbers, or raw request bodies.

## 3. Events

| # | Event | Level | Trigger | Key `detail` fields |
|---|---|---|---|---|
| 1 | `auth.login.success` | INFO | Valid login | — |
| 2 | `auth.login.failed` | WARN | Wrong password/unknown user | attempted username |
| 3 | `auth.account.locked` | WARN | Lockout triggered or attempt on locked account | failure count, lock expiry |
| 4 | `auth.token.invalid` | WARN | Invalid/missing/malformed/expired token | reason code |
| 5 | `authz.access.denied` | WARN | Ownership/role failure | resource type, resource ID, required role |
| 6 | `account.viewed` | INFO | Read own account | accountId |
| 7 | `transfer.created` | INFO | Transfer accepted | transferId, amountMinor, source, destination |
| 8 | `transfer.completed` | INFO | Status COMPLETED | transferId |
| 9 | `transfer.pending_approval` | INFO | Status PENDING_APPROVAL | transferId, reason |
| 10 | `transfer.blocked` | WARN | Status BLOCKED | transferId, reason |
| 11 | `transfer.insufficient_funds` | WARN | Insufficient balance | transferId |
| 12 | `transfer.invalid_amount` | WARN | Invalid amount | reason |
| 13 | `fraud.allow` | INFO | Fraud decision ALLOW | rule/score |
| 14 | `fraud.review` | WARN | Fraud decision REVIEW | rule |
| 15 | `fraud.block` | WARN | Fraud decision BLOCK | rule |
| 16 | `limit.exceeded` | WARN | Limit exceeded | limit type, limit value |
| 17 | `idempotency.replayed` | INFO | Same key+body replay | key, original transferId |
| 18 | `idempotency.conflict` | WARN | Same key, diff body | key |
| 19 | `approval.approved` | INFO | Checker approves | transferId, approver |
| 20 | `approval.rejected` | INFO | Checker rejects | transferId, approver |
| 21 | `approval.self_approval_denied` | WARN | Self-approval | transferId, actor |
| 22 | `reversal.created` | INFO | Reversal posted | original transferId, reversal transferId |
| 23 | `reversal.duplicate_denied` | WARN | Duplicate reversal | original transferId |
| 24 | `reconciliation.passed` | INFO | Balanced | run ID, totals |
| 25 | `reconciliation.failed` | ERROR | Discrepancy | run ID, delta |
| 26 | `audit.chain.ok` | INFO | Valid chain | sequence count, last sequence |
| 27 | `audit.chain.broken` | ERROR | Broken chain | sequence number |
| 28 | `request.invalid` | WARN | Invalid request | reason code |
| 29 | `rate.limited` | WARN | 429 returned | route, actor/IP |
| 30 | `auth.logout` | INFO | Logout | actor, role |

## 4. Quick mapping

| Outcome | HTTP | Events |
|---|---|---|
| Transfer completed | 201 | `transfer.created`, `fraud.allow`, `transfer.completed` |
| Pending approval (REVIEW) | 202 | `transfer.created`, `fraud.review`, `transfer.pending_approval` |
| Blocked | 422 | `transfer.created`, `fraud.block`, `transfer.blocked` |
| Limit exceeded | 422 | `limit.exceeded`, `transfer.blocked` |
| Insufficient funds | 422 | `transfer.insufficient_funds` |
| Replay (same body) | original | `idempotency.replayed` |
| Replay (different body) | 409 | `idempotency.conflict` |
| Locked account | 423 | `auth.account.locked` |
| Non-owned resource | 404 | `authz.access.denied` |
| Wrong role | 403 | `authz.access.denied` |

## 5. Potential additions

| Event | Purpose |
|---|---|
| `approval.invalid_state` | Specific event for approving/rejecting non-pending transfer |
| `reversal.denied` | Denial beyond duplicates (role/state) |
| `audit.viewed` | Audit log reads by MAKER/CHECKER |
| `reconciliation.run_denied` | Non-CHECKER attempts to run reconciliation |
