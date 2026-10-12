# Risk Register and Specification Gaps: APP04

| Field   | Value                                                                  |
| ------- | ---------------------------------------------------------------------- |
| Owner   | Security Lead                                                          |
| Version | 1.1                                                                    |
| Date    | 12 Oct 2026                                                            |
| Status  | Open items need team decisions before the 13 Oct freeze                |
| Method  | ISO/IEC 27005 and ISACA risk register conventions                      |
| Related | `threat-model.md`, `security-objectives.md`, `live-defence-runbook.md` |

Likelihood and impact are taken from `threat-model.md` (High, Medium, Low). Residual rating, treatment decision and owner are decided by the team and left blank until then.

## 1. Specification gaps

Gaps are places where the Team Build Plan is silent or ambiguous and a test cannot be written without a decision.

| Gap | Issue | Risk | Decision needed |
|---|---|---|---|
| G1 | Rate limiting has no owner, though 429 and `rate.limited` exist | Brute force, flooding | Assign an owner |
| G2 | Idempotency key scope unclear | Cross-user cached response | Scope per user and endpoint; define behaviour after a 422 |
| G3 | 405, 413 and 415 not in the status list | Inconsistent errors | Agree codes; keep the standard JSON error body |
| G4 | Logout with stateless tokens | Token reuse after logout | Denylist, short expiry, or accepted risk |
| G5 | Reversal by MAKER alone | Insider abuse | Mitigation or accepted risk |
| G6 | No funds reservation on PENDING_APPROVAL | Balance over-commit | Reserve funds, or re-check at approval |
| G7 | UTC day boundary versus Nairobi (UTC+3) | Limit boundary confusion | Document and test |
| G8 | Events missing: logout, invalid approval state, reversal denied, audit read | Detection gaps | Agree additions to `Actions.java` (see `log-actions.md`, section 5) |
| G9 | Seed and demo credentials in the image | Default credential exposure | Agree handling |
| G10 | 404 versus 403 for non-owned resources not fixed per endpoint | Untestable behaviour | Fix the policy per endpoint |

## 2. Risk register

| ID | Gap | Threat | Risk statement | Likelihood / Impact | Treatment (Mitigate, Accept, Transfer, Avoid) | Decision or control | Residual | Owner | Review date | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RR-01 | G1 | T1, T28 | Brute force or flooding with no rate limiting | High / High (T1); High / Medium (T28) | | | | | | Open |
| RR-02 | G2 | T14 | Idempotency response served across users | Medium / Medium | | | | | | Open |
| RR-03 | G4 | T5 | Token valid after logout | Medium / High | | | | | | Open |
| RR-04 | G5 | T17, T18 | Single-maker reversal without second approval | High / High (T17); Medium / High (T18) | | | | | | Open |
| RR-05 | G6 | T39 | Pending transfers exceed balance | Medium / High | | | | | | Open |
| RR-06 | G7 | T43 | UTC daily window differs from Nairobi time | Medium / Medium | | | | | | Open |
| RR-07 | G8 | T24 | Security events not logged | Medium / Medium | | | | | | Open |
| RR-08 | G9 | T35 | Default or seed credentials in image | Medium / High | | | | | | Open |
| RR-09 | none | T42 | Maker and checker collusion cannot be fully prevented | Low / High | | | | | | Open |
| RR-10 | none | T37 | Unpinned or vulnerable dependencies | Medium / Medium | | | | | | Open |

## 3. Acceptance record

Every accepted risk needs an accountable approver, a rationale, a compensating control and an expiry. P1 objective failures accepted at the release gate are recorded here too.

| Risk | Linked objective | Accepted by | Date | Rationale | Compensating control | Expiry |
|---|---|---|---|---|---|---|

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 12 Oct 2026 | Initial register |
| 1.1 | 12 Oct 2026 | Moved gaps G1 to G10 here from the playbook; re-keyed to T-numbers; aligned ratings with the threat model; added objective link and compensating control to the acceptance record |
