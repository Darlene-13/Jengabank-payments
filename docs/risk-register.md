# Risk Register and Specification Gaps: APP04

| Field   | Value                                                                  |
| ------- | ---------------------------------------------------------------------- |
| Owner   | Security Lead                                                          |
| Version | 1.2                                                                    |
| Date    | 13 Oct 2026                                                            |
| Method  | ISO/IEC 27005                                                           |
| Related | `threat-model.md`, `security-objectives.md`, `live-defence-runbook.md` |

Likelihood and impact are taken from `threat-model.md` (High, Medium, Low). Residual rating, treatment decision and owner are decided by the team and left blank until then.

## 1. Specification gaps

Gaps are places where the Team Build Plan is silent or ambiguous and a test cannot be written without a decision.

| Gap | Issue | Risk | Decision needed |
|---|---|---|---|
| G1 | Rate limiting scope | Brute force, flooding | Mitigated: login limited to 10 requests/minute per IP, 5 failed attempts -> 15-minute lockout; list endpoints 30/minute; transfers 5/minute; unknown destination lookup 5 per 10 minutes |
| G2 | Idempotency key scope unclear | Cross-user cached response | Mitigated: keys are scoped per user and endpoint; a `422` refusal stores the response and replays it for the same key |
| G3 | 405, 413 and 415 not in the status list | Inconsistent errors | Mitigated: standard JSON error bodies are returned for `405 METHOD_NOT_ALLOWED`, `413 PAYLOAD_TOO_LARGE` and `415 UNSUPPORTED_MEDIA_TYPE` |
| G4 | Logout invalidation | Token reuse after logout | Mitigated: logging out revokes the server-side session immediately |
| G5 | Reversal by MAKER alone | Insider abuse | Accepted residual risk: reversal of KES 250,000+ requires a different checker; below threshold the maker can reverse without a second approver, with every reversal audited |
| G6 | No funds reservation on PENDING_APPROVAL | Balance over-commit | Mitigated: funds are not reserved; approval re-checks the balance and rejects with `INSUFFICIENT_FUNDS`, while a DB trigger prevents overdrafts |
| G7 | UTC day boundary versus Nairobi (UTC+3) | Limit boundary confusion | Mitigated: the daily limit resets at Nairobi midnight (21:00 UTC) |
| G8 | Events missing | Detection gaps | Mitigated: event list updated to match `Actions.java`, including `reversal.denied`, `reversal.pending_approval`, `db.guard_triggered` and `http.request` |
| G9 | Seed and demo credentials in the image | Default credential exposure | Accepted risk: demo passwords are random 20-character strings, bcrypt-hashed through migration V8; plaintext is shared only in the team chat |
| G10 | 404 versus 403 for non-owned resources not fixed per endpoint | Untestable behaviour | Mitigated: non-owned resource requests return `404`; wrong-role requests return `403` |
| G11 | Invalid pagination limit behavior differs from PG-01 | Limits outside 1–100 are silently clamped by the current implementation instead of rejected | Decide and align implementation with the API/test-plan contract |

## 2. Risk register

| ID | Gap | Threat | Risk statement | Likelihood / Impact | Treatment (Mitigate, Accept, Transfer, Avoid) | Decision or control | Residual | Owner | Review date | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RR-01 | G1 | T1, T28 | Brute force or flooding | High / High (T1); High / Medium (T28) | Mitigate | Login 10/min/IP; 15-minute lockout after 5 failures; list endpoints 30/min; transfers 5/min; unknown destination lookups 5/10 min | Low | Security Lead | 2026-10-20 | Mitigated |
| RR-02 | G2 | T14 | Idempotency response served across users | Medium / Medium | Mitigate | Keys are scoped per user and endpoint; a `422` refusal is stored and replayed for the same key | Low | Build Lead | 2026-10-20 | Mitigated |
| RR-03 | G4 | T5 | Token valid after logout | Medium / High | Mitigate | Logout revokes the server-side session immediately | Low | Dev 2 | 2026-10-20 | Mitigated |
| RR-04 | G5 | T17, T18 | Single-maker reversal without second approval | High / High (T17); Medium / High (T18) | Accept | KES 250,000+ requires a different checker; below threshold the maker can reverse without a second approver and every reversal is audited | Medium | Security Lead | 2026-10-20 | Accepted |
| RR-05 | G6 | T39 | Pending transfers exceed balance | Medium / High | Mitigate | Approval re-checks the balance and fails with `INSUFFICIENT_FUNDS`; DB trigger prevents overdrafts | Low | Build Lead | 2026-10-20 | Mitigated |
| RR-06 | G7 | T43 | Daily limit window differs from Nairobi time | Medium / Medium | Mitigate | Daily total resets at Nairobi midnight, i.e. 21:00 UTC | Low | Security Lead | 2026-10-20 | Mitigated |
| RR-07 | G8 | T24 | Security events not logged | Medium / Medium | Mitigate | `Actions.java` and the logging contract are updated to the 35 current events | Low | Dev 2 | 2026-10-20 | Mitigated |
| RR-08 | G9 | T35 | Default or seed credentials in image | Medium / High | Accept | Random 20-character demo passwords are bcrypt-hashed via migration V8; plaintext shared only in team chat | Medium | Security Lead | 2026-10-20 | Accepted |
| RR-09 | none | T42 | Maker and checker collusion cannot be fully prevented | Low / High | Accept | Audit trail records initiator and approver; reconciliation and alerts support detection | Medium | Security Lead | 2026-10-20 | Accepted |
| RR-10 | none | T37 | Unpinned or vulnerable dependencies | Medium / Medium | Accept | Versioned, scanned images and package review per release gate | Medium | Build Lead | 2026-10-20 | Accepted |
| RR-11 | G11 | T34 | Pagination boundary policy mismatch | Medium / Low | Mitigate | Validate cursor and enforce the documented pagination policy | Low | Build Lead | 2026-10-20 | Mitigated |

## 3. Acceptance record

| Risk | Linked objective | Accepted by | Date | Rationale | Compensating control | Expiry |
|---|---|---|---|---|---|---|
