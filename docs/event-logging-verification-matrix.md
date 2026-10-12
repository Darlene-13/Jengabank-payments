# Security Event Logging Verification Matrix

| Field     | Value                                                         |
| --------- | ------------------------------------------------------------- |
| Owner     | Security Lead                                                 |
| Version   | 1.1                                                           |
| Date      | 12 Oct 2026                                                   |
| Status    | Ready for use per Healthy tag                                 |
| Source    | `log-actions.md` (28 events from Team Build Plan, section 7)  |
| Objective | SO-20 (every event fires), SO-21 (no secrets, no log forging) |
| Tests     | LG-01, LG-02, LG-03 in `security-test-plan.md`                |

Tag tested: ________  Tester: ________  Date (UTC): ________

## 1. Log line checklist (apply to every event)

- [ ] Valid single-line JSON
- [ ] UTC ISO 8601 timestamp
- [ ] `requestId` present and equal to the response `X-Request-Id`
- [ ] Actor (user ID or `anonymous`) and outcome present
- [ ] No tokens, passwords or full sensitive account details
- [ ] Event name exactly matches the constant in `Actions.java`

Required fields per event are in `log-actions.md`, section 3.

## 2. Matrix

| # | Event | How to trigger | Test | Fields present | Result | Evidence |
|---|---|---|---|---|---|---|
| 1 | `auth.login.success` | Valid login | smoke | | | |
| 2 | `auth.login.failed` | Wrong password; unknown user | AT-01, AT-02 | | | |
| 3 | `auth.account.locked` | Exceed failure threshold | AT-01, AT-03 | | | |
| 4 | `auth.token.invalid` | Tampered, expired or missing token | AT-04 | | | |
| 5 | `authz.access.denied` | Cross-user access; wrong role | AZ-01, AZ-02 | | | |
| 6 | `account.viewed` | GET own account | smoke | | | |
| 7 | `transfer.created` | POST transfer | FR-01 | | | |
| 8 | `transfer.completed` | Transfer passing checks | FR-01 | | | |
| 9 | `transfer.pending_approval` | Fraud REVIEW | FR-02 | | | |
| 10 | `transfer.blocked` | Fraud BLOCK or limit | FR-03, LM-01 | | | |
| 11 | `transfer.insufficient_funds` | Transfer above balance | CC-01 | | | |
| 12 | `transfer.invalid_amount` | Zero or negative amount | AM-01 | | | |
| 13 | `fraud.allow` | Low-risk transfer | FR-01 | | | |
| 14 | `fraud.review` | Medium-risk transfer | FR-02 | | | |
| 15 | `fraud.block` | High-risk transfer | FR-03 | | | |
| 16 | `limit.exceeded` | Over any limit | LM-01 to LM-03 | | | |
| 17 | `idempotency.replayed` | Same key, same body | ID-02 | | | |
| 18 | `idempotency.conflict` | Same key, different body | ID-04 | | | |
| 19 | `approval.approved` | Checker approves | MC-03 | | | |
| 20 | `approval.rejected` | Checker rejects | MC-03 | | | |
| 21 | `approval.self_approval_denied` | Checker approves own item | MC-01 | | | |
| 22 | `reversal.created` | Maker reverses a completed transfer | RV-01 | | | |
| 23 | `reversal.duplicate_denied` | Second reversal | RV-01, RV-02 | | | |
| 24 | `reconciliation.passed` | Clean run | RC-01 | | | |
| 25 | `reconciliation.failed` | Injected imbalance (DB copy) | RC-01 | | | |
| 26 | `audit.chain.broken` | Tamper (DB copy), then verify | AU-01 | | | |
| 27 | `request.invalid` | Bad JSON, oversize body, unknown field | IN-02 to IN-04 | | | |
| 28 | `rate.limited` | Burst on login | RL-01 | | | |

Events verified: __ / 28

## 3. Detection coverage

For each attack in `security-test-plan.md`, confirm at least one event fires. List any attack with no event as a High finding. Known attacks with no plan event today: AT-05 (logout), MC-02 (invalid approval state), RV-05 (reversal denied), reads of `/audit`. See `log-actions.md`, section 5 for the proposed additions (gap G8).

## 4. Findings

| ID | Severity | Event | Description | Owner |
|---|---|---|---|---|

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 12 Oct 2026 | Initial matrix |
| 1.1 | 12 Oct 2026 | Corrected event count to 28; removed reserved row; linked each event to its test; moved field definitions to `log-actions.md` |
