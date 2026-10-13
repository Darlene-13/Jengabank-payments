# Security Event Logging Verification Matrix

| Field     | Value                                                         |
| --------- | ------------------------------------------------------------- |
| Owner     | Security Lead                                                 |
| Version   | 1.1                                                           |
| Date      | 12 Oct 2026                                                   |
| Source    | `log-actions.md` (30 events)  |
| Objective | SO-20 (every event fires), SO-21 (no secrets, no log forging) |
| Tests     | LG-01, LG-02, LG-03 in `security-test-plan.md`                |

Tag tested: ________  Tester: ________  Date (UTC): ________

## 1. Checklist

- [ ] Valid single-line JSON
- [ ] UTC ISO 8601 timestamp
- [ ] `requestId` matches response `X-Request-Id`
- [ ] Actor (user ID or `anonymous`) and outcome present
- [ ] No tokens, passwords, or full sensitive account details
- [ ] Event name matches Actions.java

Required fields per event: see `log-actions.md`.

## 2. Matrix

| # | Event | How to trigger | Test | Fields present | Result | Evidence |
|---|---|---|---|---|---|---|
| 1 | `auth.login.success` | Valid login | smoke | | | |
| 2 | `auth.login.failed` | Wrong password; unknown user | AT-01, AT-02 | | | |
| 3 | `auth.account.locked` | Lockout triggered | AT-01, AT-03 | | | |
| 4 | `auth.token.invalid` | Tampered/expired/missing token | AT-04 | | | |
| 5 | `authz.access.denied` | Cross-user or wrong role | AZ-01, AZ-02 | | | |
| 6 | `account.viewed` | GET own account | smoke | | | |
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
| 26 | `audit.chain.ok` | Valid chain | AU-01 | | | |
| 27 | `audit.chain.broken` | Broken chain | AU-01 | | | |
| 28 | `request.invalid` | Bad/invalid request | IN-02–IN-04 | | | |
| 29 | `rate.limited` | Rate limited | RL-01 | | | |
| 30 | `auth.logout` | Logout | AT-05 | | | |

Events verified: __ / 30

## 3. Detection coverage

Confirm each attack in `security-test-plan.md` fires at least one event. Note attacks with no explicit event (MC-02, RV-05, audit reads) - see `log-actions.md` section 5.

## 4. Findings

| ID | Severity | Event | Description | Owner |
|---|---|---|---|---|
