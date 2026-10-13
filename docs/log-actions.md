# Log Actions: Shared Security Event Names (APP04)

| Field | Value |
|---|---|
| Owner | Dev 2 (logging) |
| Version | 1.2 |
| Date | 13 Oct 2026 |
| Related | `event-logging-verification-matrix.md`, `security-test-plan.md` |

## 1. Purpose

This is the reference for every security event the application logs. It defines the exact string constants for `Actions.java`, who emits each one, when, and which fields must be present. The verification matrix records the test result for each event; this file does not.

## 2. Log line format

Logs follow the JengaSec contract: one structured JSON object per line.

Required fields for request/decision records are:

- `ts` (UTC ISO-8601)
- `src_ip`
- `method`
- `path`
- `status`
- `user` (empty string when unauthenticated)
- `action`
- `requestId`

`requestId` must match the response header `X-Request-Id`. `path` should be the route template without sensitive query-string values. Do not log passwords, tokens, signing keys, password hashes, full account numbers, or raw request bodies.

Security events (`logger_name: security`) raised inside an authenticated request always carry `username`, including rejections such as validation errors, unreadable bodies and role denials, so events can be grouped by user without a `requestId` join.

The runtime may include additional fields, but the verification matrix validates the JengaSec contract above rather than the older `actor/anonymous`, `event`, or `timestamp` naming.

## 3. Events

This list matches the current `Actions.java` constants.

| # | Event | Level | Trigger | Key `detail` fields |
|---|---|---|---|---|
| 1 | `http.request` | INFO | Incoming request entry | method, path, src_ip, user |
| 2 | `server.error` | ERROR | Unhandled server exception | error class, path |
| 3 | `db.guard_triggered` | WARN | Database guard/constraint block | table, rule |
| 4 | `auth.login.success` | INFO | Valid login | username |
| 5 | `auth.login.failed` | WARN | Wrong password/unknown user | attempted username |
| 6 | `auth.account.locked` | WARN | Lockout triggered or attempt on locked account | failure count, lock expiry |
| 7 | `auth.token.invalid` | WARN | Invalid, revoked, expired, malformed or query-string token | reason code |
| 8 | `auth.logout` | INFO | Logout | user, role |
| 9 | `authz.access.denied` | WARN | Ownership/role failure | username; ownership: resource type, resource ID; role: `reason: role`, path, src_ip |
| 10 | `account.viewed` | INFO | Read own account | accountId |
| 11 | `transfer.created` | INFO | Transfer accepted | transferId, amountMinor, source, destination |
| 12 | `transfer.completed` | INFO | Status COMPLETED | transferId |
| 13 | `transfer.pending_approval` | INFO | Status PENDING_APPROVAL | transferId, reason |
| 14 | `transfer.blocked` | WARN | Status BLOCKED | transferId, reason |
| 15 | `transfer.insufficient_funds` | WARN | Insufficient balance | transferId |
| 16 | `transfer.invalid_amount` | WARN | Invalid amount | reason |
| 17 | `fraud.allow` | INFO | Fraud decision ALLOW | rule/score |
| 18 | `fraud.review` | WARN | Fraud decision REVIEW | rule |
| 19 | `fraud.block` | WARN | Fraud decision BLOCK | rule |
| 20 | `limit.exceeded` | WARN | Limit exceeded | limit type, limit value |
| 21 | `idempotency.replayed` | INFO | Same key+body replay | key, original transferId |
| 22 | `idempotency.conflict` | WARN | Same key, different body | key |
| 23 | `approval.approved` | INFO | Checker approves | transferId, approver |
| 24 | `approval.rejected` | INFO | Checker rejects | transferId, approver |
| 25 | `approval.self_approval_denied` | WARN | Self-approval | transferId, actor |
| 26 | `reversal.created` | INFO | Reversal posted | original transferId, reversal transferId |
| 27 | `reversal.duplicate_denied` | WARN | Duplicate reversal | original transferId |
| 28 | `reversal.denied` | WARN | Reversal denied for role/state reasons beyond duplicates | original transferId, reason |
| 29 | `reversal.pending_approval` | INFO | Reversal pending checker approval | reversalId, reason |
| 30 | `reconciliation.passed` | INFO | Balanced | run ID, totals |
| 31 | `reconciliation.failed` | ERROR | Discrepancy | run ID, delta |
| 32 | `audit.chain.ok` | INFO | Valid chain | sequence count, last sequence |
| 33 | `audit.chain.broken` | ERROR | Broken chain | sequence number |
| 34 | `request.invalid` | WARN | Invalid request | reason code |
| 35 | `rate.limited` | WARN | 429 returned | route, actor/IP |

## 4. Quick mapping

| Outcome | HTTP | Events |
|---|---|---|
| Request received | 200/201/etc. | `http.request` |
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
| Logout | 204/200 | `auth.logout` |
| Reversal pending approval | 202 | `reversal.pending_approval` |
| Reversal denied | 400/403/409 | `reversal.denied` |

## 5. Implementation note

`http.request` and `db.guard_triggered` are current `Actions.java` entries; they are not optional additions after the fact.
