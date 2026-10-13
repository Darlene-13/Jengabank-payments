# Security Event Logging Verification Matrix

| Field     | Value                                                         |
| --------- | ------------------------------------------------------------- |
| Owner     | Security Lead                                                 |
| Version   | 1.3                                                           |
| Date      | 13 Oct 2026                                                   |
| Source    | `log-actions.md` (35 current events in `Actions.java`) |
| Objective | SO-20 (every event fires), SO-21 (no secrets, no log forging) |
| Tests     | LG-01 through LG-04 in `security-test-plan.md` |

Execution record: pending. Enter the image tag, tester and UTC date after running the checks.

Direct automated assertions cover 12 of 35 event names: 11 representative security events and `audit.chain.ok`, in `LoggingVerificationIT`. The remaining 23 remain unverified until a test asserts the event and its required fields. 

## 1. Checklist

- [ ] Valid single-line JSON
- [ ] UTC ISO 8601 `ts` value in the JengaSec contract
- [ ] `requestId` matches response `X-Request-Id`
- [ ] `user` is present and empty for unauthenticated requests
- [ ] No tokens, passwords, or full sensitive account details
- [ ] Event name matches `Actions.java`

Required fields per event: see `log-actions.md`.

## 2. Matrix

| # | Event | How to trigger | Test | Fields present | Result | Evidence |
|---|---|---|---|---|---|---|
| 1 | `auth.login.success` | Valid login | `LoggingVerificationIT#representativeSecurityEventsAreEmittedAsCorrelatedJson` | | | |
| 2 | `auth.login.failed` | Wrong password; unknown user | AT-01, AT-02 | | | |
| 3 | `auth.account.locked` | Lockout triggered | AT-01, AT-03 | | | |
| 4 | `auth.token.invalid` | Random or modified token, revoked token, expired session, missing/malformed Authorization header, token in the query string | AT-04 | | | |
| 5 | `authz.access.denied` | Cross-user or wrong role | AZ-01, AZ-02 | | | |
| 6 | `account.viewed` | GET own account | manual; no automated event assertion | | | |
| 7 | `transfer.created` | POST transfer | FR-01 | | | |
| 8 | `transfer.completed` | Transfer completes | FR-01 | | | |
| 9 | `transfer.pending_approval` | Fraud REVIEW | FR-02 | | | |
| 10 | `transfer.blocked` | Fraud BLOCK/limit | FR-03, LM-01 | | | |
| 11 | `transfer.insufficient_funds` | Insufficient balance | CC-01 | | | |
| 12 | `transfer.invalid_amount` | Invalid amount | AM-01 | | | |
| 13 | `fraud.allow` | ALLOW decision | FR-01 | | | |
| 14 | `fraud.review` | REVIEW decision | FR-02 | | | |
| 15 | `fraud.block` | BLOCK decision | FR-03 | | | |
| 16 | `limit.exceeded` | Limit exceeded | LM-01–LM-03 | | | |
| 17 | `idempotency.replayed` | Same key+body | ID-02 | | | |
| 18 | `idempotency.conflict` | Same key, different body | ID-04 | | | |
| 19 | `approval.approved` | Checker approves | MC-03 | | | |
| 20 | `approval.rejected` | Checker rejects | MC-03 | | | |
| 21 | `approval.self_approval_denied` | Self-approval | MC-01 | | | |
| 22 | `reversal.created` | Reversal posted | RV-01 | | | |
| 23 | `reversal.duplicate_denied` | Duplicate reversal | RV-01, RV-02 | | | |
| 24 | `reconciliation.passed` | Clean run | RC-01 | | | |
| 25 | `reconciliation.failed` | Discrepancy | RC-01 | | | |
| 26 | `audit.chain.ok` | Valid chain | `LoggingVerificationIT#auditChainOkEventFiresOnCleanVerify` | | | |
| 27 | `audit.chain.broken` | Broken chain | AU-01 | | | |
| 28 | `request.invalid` | Bad/invalid request | IN-02–IN-04 | | | |
| 29 | `rate.limited` | Rate limited | RL-01 | | | |
| 30 | `auth.logout` | Logout | AT-05 | | | |

Events verified: __ / 35 (count only events with recorded evidence and required fields checked)

## 3. Detection coverage

For each attack in `security-test-plan.md`, record an event as verified only when its log entry and required fields have been asserted. See `log-actions.md` section 5 for actions without a defined event.

## 4. Findings

| ID | Severity | Event | Description | Owner |
|---|---|---|---|---|

No findings have been recorded. Populate this register with evidence after execution.
