# Log Actions: Shared Security Event Names (APP04)

| Field | Value |
|---|---|
| Owner | Security Lead (definitions); Dev 2 (`logging/`, `Actions.java`) |
| Version | 1.0 |
| Date | 12 Oct 2026 |
| Status | Draft for team agreement |
| Source | Team Build Plan, section 7 (28 event names). Names and spelling are fixed by the plan. |
| Related | `event-logging-verification-matrix.md`, `security-test-plan.md` |

## 1. Purpose

This is the reference for every security event the application logs. It defines the exact string constants for `Actions.java`, who emits each one, when, and which fields must be present. The verification matrix records the test result for each event; this file does not.

## 2. Log line format

Per the API guidelines, logs are structured JSON, one object per line, UTC ISO 8601 timestamps, with a request ID that matches the `X-Request-Id` response header.

Fields required by the plan: `timestamp`, `event`, `requestId`.

Fields proposed for every line:

| Field            | Type   | Notes                                                                                           |
| ---------------- | ------ | ----------------------------------------------------------------------------------------------- |
| `timestamp`      | string | UTC ISO 8601, for example `2026-10-13T08:15:30.123Z`                                            |
| `level`          | string | `INFO` for expected outcomes, `WARN` for denials and rejections, `ERROR` for integrity failures |
| `event`          | string | Exact constant from section 3                                                                   |
| `requestId`      | string | Echo of `X-Request-Id`, or generated; length-capped and sanitised                               |
| `actor`          | string | User ID, or `anonymous`                                                                         |
| `role`           | string | `CUSTOMER`, `MAKER`, `CHECKER`, or `none`                                                       |
| `ip`             | string | Client IP                                                                                       |
| `method`, `path` | string | Path template, never a query string containing secrets                                          |
| `status`         | number | HTTP status returned                                                                            |
| `outcome`        | string | `SUCCESS`, `DENIED` or `FAILURE`                                                                |
| `detail`         | object | Event-specific fields from section 3                                                            |

Never log: passwords, tokens, signing keys, password hashes, full account numbers, or raw request bodies. Values from user input are written through the JSON encoder so control characters and line breaks cannot forge entries.

## 3. Events defined by the plan

Constant names are the dotted value in upper snake case, for example `AUTH_LOGIN_SUCCESS = "auth.login.success"`.

| # | Event (string value) | Emitter | Level | Trigger | Required `detail` fields |
|---|---|---|---|---|---|
| 1 | `auth.login.success` | Dev 2 | INFO | Valid login | none beyond envelope |
| 2 | `auth.login.failed` | Dev 2 | WARN | Wrong password or unknown user | attempted username; no password |
| 3 | `auth.account.locked` | Dev 2 | WARN | Failure threshold reached; also login attempt on a locked account | failure count, lock expiry |
| 4 | `auth.token.invalid` | Dev 2 | WARN | Missing, malformed, expired, tampered or wrongly signed token | reason code; never the token |
| 5 | `authz.access.denied` | Dev 2 and every owner of a protected route | WARN | Ownership failure or insufficient role | resource type, resource ID, required role |
| 6 | `account.viewed` | Dev 2 | INFO | Authenticated read of an own account | accountId |
| 7 | `transfer.created` | Build Lead | INFO | Transfer request accepted for processing | transferId, amountMinor, source and destination account IDs |
| 8 | `transfer.completed` | Build Lead | INFO | Status COMPLETED, funds moved | transferId |
| 9 | `transfer.pending_approval` | Build Lead | INFO | Status PENDING_APPROVAL | transferId, reason |
| 10 | `transfer.blocked` | Build Lead | WARN | Status BLOCKED by fraud or limit | transferId, reason |
| 11 | `transfer.insufficient_funds` | Build Lead | WARN | Debit exceeds available balance | transferId |
| 12 | `transfer.invalid_amount` | Build Lead | WARN | Zero, negative or overflowing amount | reason |
| 13 | `fraud.allow` | Dev 3 | INFO | Fraud decision ALLOW | rule or score |
| 14 | `fraud.review` | Dev 3 | WARN | Fraud decision REVIEW | rule |
| 15 | `fraud.block` | Dev 3 | WARN | Fraud decision BLOCK | rule |
| 16 | `limit.exceeded` | Dev 3 | WARN | Per-transfer, daily or velocity limit hit | limit type, limit value |
| 17 | `idempotency.replayed` | Build Lead | INFO | Same key, same body; original response returned | key, original transferId |
| 18 | `idempotency.conflict` | Build Lead | WARN | Same key, different body | key |
| 19 | `approval.approved` | Dev 3 | INFO | Checker approves a PENDING_APPROVAL transfer | transferId, approver |
| 20 | `approval.rejected` | Dev 3 | INFO | Checker rejects a PENDING_APPROVAL transfer | transferId, approver |
| 21 | `approval.self_approval_denied` | Dev 3 | WARN | Approver equals initiator | transferId, actor |
| 22 | `reversal.created` | Build Lead | INFO | Reversal posted | original transferId, reversal transferId |
| 23 | `reversal.duplicate_denied` | Build Lead | WARN | Second reversal of the same transfer | original transferId |
| 24 | `reconciliation.passed` | Dev 3 | INFO | Reconciliation run balanced | run ID, totals |
| 25 | `reconciliation.failed` | Dev 3 | ERROR | Reconciliation found a discrepancy | run ID, delta |
| 26 | `audit.chain.broken` | Dev 3 | ERROR | `/audit/verify` finds a break | sequence number |
| 27 | `request.invalid` | Build Lead (`error/`) | WARN | Validation failure, malformed JSON, unknown field, oversize body | reason code |
| 28 | `rate.limited` | Unassigned (gap G1) | WARN | 429 returned | route; actor or IP |

## 4. Event-to-outcome map

| Situation                  | HTTP            | Events                                                          |
| -------------------------- | --------------- | --------------------------------------------------------------- |
| Transfer passes all checks | 201             | `transfer.created`, `fraud.allow`, `transfer.completed`         |
| Fraud REVIEW               | 202             | `transfer.created`, `fraud.review`, `transfer.pending_approval` |
| Fraud BLOCK                | 422             | `transfer.created`, `fraud.block`, `transfer.blocked`           |
| Limit hit                  | 422             | `limit.exceeded`, `transfer.blocked`                            |
| Insufficient funds         | 422             | `transfer.insufficient_funds`                                   |
| Replay, same body          | original status | `idempotency.replayed`                                          |
| Replay, different body     | 409             | `idempotency.conflict`                                          |
| Locked account             | 423             | `auth.account.locked`                                           |
| Non-owned resource         | 404             | `authz.access.denied`                                           |
| Wrong role                 | 403             | `authz.access.denied`                                           |

## 5. Proposed additions (not in the plan)

Raised under gap G8. Do not add to `Actions.java` until the Build Lead and Dev 2 agree; the plan's list is the contract.

| Proposed event | Why |
|---|---|
| `auth.logout` | No record of session end (gap G4) |
| `approval.invalid_state` | Approve or reject on a non-pending transfer is currently only a generic `request.invalid` |
| `reversal.denied` | Reversal refused for role or state, other than duplicates |
| `audit.viewed` | Reads of the audit log by MAKER or CHECKER |
| `reconciliation.run_denied` | Non-CHECKER attempt to start a run |

## 6. Change log

| Version | Date        | Change                                |
| ------- | ----------- | ------------------------------------- |
| 1.0     | 12 Oct 2026 | Initial reference from plan section 7 |
|         |             |                                       |
