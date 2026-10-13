# Security Objectives: JengaBank Payments (APP04)

| Field            | Value                                                                                                                              |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Owner            | Security Lead                                                                                                                      |
| Version          | 1.1                                                                                                                                |
| Date             | 13 Oct 2026                                                                                                                        |
| Related          | `threat-model.md`, `security-test-plan.md`, `log-actions.md`, `risk-register.md`                                                    |

## 1. Purpose

Each objective is a claim that tests can prove false. An objective is **met** only if all linked tests pass on a build tagged Healthy. Objectives trace to threats in `threat-model.md`, tests in `security-test-plan.md`, and events in `log-actions.md`. Evidence is kept per tag.

## 2. Principles

1. Money integrity is never traded for convenience.
2. Authorization is enforced on the server for every protected operation.
3. Every security-relevant decision is logged.
4. Controls are tested on every Healthy release.
5. Known weaknesses are recorded honestly.

## 3. Priority levels

| Priority | Meaning | Gate rule |
|---|---|---|
| P0 | Release blocker: money integrity, authorization, secrets, Healthy image | Must pass, no exceptions |
| P1 | Must be fixed before submission if time allows | Fix, or record as residual risk with owner, reason and compensating control |
| P2 | Should be fixed | Does not block release |

Status values: Not tested, Met, Partially met, Not met, Accepted risk.

## 4. Objectives

Evidence for every objective is stored under `evidence/<tag>/<test-id>/`. Test IDs and events are defined in the test plan and `log-actions.md`.

### 4.1 Money integrity

| ID    | Objective                                                                                                                                          | Pri | Success criteria                                                                                                    | Threats  | Tests               | Events                                         | ASVS / OWASP |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------- | --- | ------------------------------------------------------------------------------------------------------------------- | -------- | ------------------- | ---------------------------------------------- | ------------ |
| SO-01 | For every journal, total debits equal total credits                                                                                                | P0  | Invariant SQL 1 returns no rows after every test run                                                                | T16      | LD-01               | `reconciliation.failed`                        | V11, API6    |
| SO-02 | No sequence of API calls, including parallel ones, changes the total ledger balance; money is only moved                                           | P0  | Invariant SQL 2 is zero after CC-01 and every run                                                                   | T11, T16 | LD-01, CC-01        | none                                           | V11, API6    |
| SO-03 | No account can be overdrawn, even under N parallel transfers from one account | P0 | 20 parallel debits of 100 against a balance of 1000: at most 10 succeed; invariant SQL 1–3 clean | T11 | CC-01 | `transfer.insufficient_funds` | V11, API6 |
| SO-04 | A money-changing POST without an Idempotency-Key is rejected with 400 and moves no funds                                                           | P0  | 400 for a missing and a non-UUID key; ledger unchanged                                                              | T12      | ID-01               | `request.invalid`                              | V13, API6    |
| SO-05 | Same key and same body returns the original response and moves funds once; same key with a different body returns 409                              | P0  | Replay returns original response, one ledger posting; mismatch returns 409; applies to approve, reject and reversal | T12      | ID-02, ID-03, ID-04 | `idempotency.replayed`, `idempotency.conflict` | V11, API6    |
| SO-06 | Concurrent requests with the same key produce exactly one transfer                                                                                 | P0  | 20 parallel same-key requests create one transfer; invariant SQL 5 clean                                            | T13      | ID-05               | `idempotency.replayed`                         | V11, API6    |
| SO-07 | An idempotency key from one user cannot be used to read or affect another user's transfer                                                          | P0  | No cross-user response or effect (gap G2 resolved as per user and endpoint)                                         | T14      | ID-06               | `idempotency.conflict`                         | V4, API1     |
| SO-08 | Only a positive `amountMinor` within long range, with `currency: "KES"`, to an existing and different account, is accepted                         | P0  | All other inputs return 400 or 422 and move no funds                                                                | T15      | AM-01 to AM-06      | `transfer.invalid_amount`, `request.invalid`   | V5, API6     |
| SO-09 | A transfer can be reversed at most once; only COMPLETED transfers are reversible; a reversal is a journal entry and cannot itself be reversed | P0  | Parallel reversals give one success; invariant SQL 6 and 7 clean                                                    | T17      | RV-01 to RV-03      | `reversal.duplicate_denied`                    | V11, API6    |
| SO-10 | Per-transfer, daily and velocity limits cannot be bypassed by splitting or racing requests                                                         | P1  | At, below and above each limit return the correct status; parallel requests do not exceed limits in aggregate       | T19      | LM-01 to LM-03      | `limit.exceeded`                               | V11, API6    |

### 4.2 Identity and access

| ID | Objective | Pri | Success criteria | Threats | Tests | Events | ASVS / OWASP |
|---|---|---|---|---|---|---|---|
| SO-11 | Every protected endpoint rejects missing, malformed, revoked, expired or otherwise invalid session tokens with 401 | P0 | All token variants in AT-04 return 401 | T4 | AT-04 | `auth.token.invalid` | V3, API2 |
| SO-12 | A user can never read another user's accounts, entries or transfers; non-owned resources return 404 | P0 | Zero leakage across cross-user tests; same body as a nonexistent ID | T7 | AZ-01 | `authz.access.denied` | V4, API1 |
| SO-13 | A role cannot call endpoints reserved for a higher role | P0 | Role matrix over all 23 endpoints matches the contract | T8 | AZ-02 | `authz.access.denied` | V4, API5 |
| SO-14 | Identity and role are derived only from the token; values in body, query or headers are ignored | P0 | Injected identity and role values have no effect | T9 | AZ-03 | none | V3, API5 |
| SO-15 | A user cannot approve their own pending transfer, and reversal is possible only for the MAKER role [confirm with Build Lead whether any user holds more than one role] | P0 | Self-approval returns 403 or 422; reversal by CUSTOMER, CHECKER or anonymous is refused | T18, T20 | MC-01, RV-05 | `approval.self_approval_denied`, `authz.access.denied` | V4, API5 |
| SO-16 | An approval or rejection applies only to a transfer in PENDING_APPROVAL, and only once, even under parallel requests | P0 | One outcome and one posting for every invalid or parallel sequence | T21 | MC-02, MC-03 | `approval.approved`, `approval.rejected` | V11, API6 |
| SO-17 | Repeated failed logins lock the account and the lockout expires; login responses for an unknown user and a wrong password are indistinguishable in content and timing | P1 | 423 after the threshold [threshold to be agreed]; identical 401 body and similar timing; lockout expires | T1, T2, T3 | AT-01, AT-02, AT-03 | `auth.login.failed`, `auth.account.locked` | V2, API2 |
| SO-18 | After logout, the token can no longer be used | P1 | Old token rejected (DB-backed session revocation on logout) | T5 | AT-05 | `auth.logout` | V3, API2 |
| SO-19 | Rate limits throttle login and transfer flooding with 429 | P1 | 429 under burst on login (implemented); transfers/general API throttling defined per policy | T1, T28 | RL-01 | `rate.limited` | V13, API4 |
| SO-37 | Staff visibility on transfers is limited to its defined scope; customers see only their own | P1 | Staff list and view all transfers; customers list and view own only | T10 | AZ-04 | `authz.access.denied` | V4, API5 |

### 4.3 Transaction controls

| ID | Objective | Pri | Success criteria | Threats | Tests | Events | ASVS / OWASP |
|---|---|---|---|---|---|---|---|
| SO-38 | Fraud decisions map to the correct outcomes | P1 | ALLOW gives 201 COMPLETED, REVIEW gives 202 PENDING_APPROVAL, BLOCK gives 422 BLOCKED with no funds moved | T22 | FR-01 to FR-03 | `fraud.allow`, `fraud.review`, `fraud.block` | V11, API6 |
| SO-39 | Funds committed to pending transfers are accounted for | P1 | Many pending transfers exceeding the balance do not over-commit (gap G6 resolved) | T39 | MC-03 | `transfer.insufficient_funds` | V11, API6 |
| SO-40 | No deadlock or 500 under opposing parallel transfers | P1 | Repeated A to B and B to A runs give zero 500s and zero hangs | T40 | CC-01 | none | V11, API4 |
| SO-41 | A flood of pending transfers cannot clog the checker queue | P2 | Per-user pending cap or velocity limit and 429 under burst | T41 | RL-01, LM-03 | `limit.exceeded`, `rate.limited` | API4 |
| SO-42 | Maker-checker collusion is detectable even though it cannot be prevented | P2 | Initiator and approver recorded on every approval and reversal; reconciliation flags imbalances | T42 | LG-01, RC-01 | `approval.approved`, `reversal.created` | V7 |
| SO-43 | The daily limit window is defined in Nairobi time and tested at the Nairobi-midnight boundary | P2 | Behaviour across Nairobi midnight (21:00 UTC) matches the documented window (gap G7) | T43 | LM-02 | `limit.exceeded` | V11 |

### 4.4 Detection and evidence

| ID | Objective | Pri | Success criteria | Threats | Tests | Events | ASVS / OWASP |
|---|---|---|---|---|---|---|---|
| SO-20 | Every event in the shared event list fires when its trigger occurs, with the JengaSec log contract (`ts`, `src_ip`, `method`, `path`, `status`, `user`, `action`, `requestId`) | P0 | All 35 current `Actions.java` events are explicitly verified in the logging matrix, including required fields and no secret leakage | T24 | LG-01 | all 35 | V7 |
| SO-21 | Logs contain no passwords, tokens or full account details, and user input cannot forge log lines | P1 | Searches return nothing; CRLF and oversize inputs keep logs valid single-line JSON | T6, T24 | LG-02, LG-03 | none | V7 |
| SO-22 | Modifying or deleting any audit row is detected by `/audit/verify` and raises `audit.chain.broken` | P0 | Edit and delete on a DB copy both fail verification; concurrent writes do not fork the chain | T23 | AU-01 | `audit.chain.broken` | V7 |
| SO-23 | The application's DB role has no UPDATE or DELETE on the audit table | P1 | Grant check shows none | T23 | AU-02 | none | V7, V14 |
| SO-24 | Reconciliation detects a ledger discrepancy and records `reconciliation.failed` | P1 | Clean run passes; injected imbalance on a DB copy fails; only CHECKER can run it | T16, T25 | RC-01 | `reconciliation.passed`, `reconciliation.failed` | V11 |
| SO-44 | Logs survive a container restart | P2 | Events written before a restart are still available afterwards | T44 | LG-04 | none | V7 |

### 4.5 Input, platform and frontend

| ID | Objective | Pri | Success criteria | Threats | Tests | Events | ASVS / OWASP |
|---|---|---|---|---|---|---|---|
| SO-25 | SQL injection payloads in any path, query or body field have no effect and return a clean error | P0 | No data change or error leak; code review confirms parameterised queries | T26 | IN-01 | `request.invalid` | V5, API8 |
| SO-26 | Unknown JSON fields, malformed JSON and bodies over 16 KB are rejected with 400 or 413 | P1 | All three rejected with the standard error body (codes agreed, gap G3) | T27 | IN-02, IN-03, IN-04 | `request.invalid` | V5, API3, API4 |
| SO-27 | Error responses follow `{code, message, requestId}` and never expose stack traces, SQL or framework details | P1 | Every sampled error matches the schema | T29 | IL-01 | `request.invalid` | V7, API8 |
| SO-28 | No debug or actuator endpoints are exposed, `/swagger-ui` is disabled, and `/openapi.json` is reviewed and accepted as the public contract | P1 | `/swagger-ui` returns 404, `/openapi.json` remains public by team decision, and no internals are disclosed | T30 | IL-02 | none | V14, API8 |
| SO-29 | Pagination enforces a default of 50 and a maximum of 100, and rejects malformed cursors | P2 | Out-of-range limits clamped or rejected; bad cursor returns 400 | T34 | PG-01 | `request.invalid` | API4 |
| SO-30 | The PWA renders user-controlled text without executing it, and a CSP is set | P1 | No unsafe HTML sinks; stored XSS payload shown as text; CSP header present | T31 | FE-01 | none | V5 |
| SO-31 | Tokens are not in `localStorage` unless a documented exception exists, and the service worker never caches authenticated `/api` responses | P1 | Storage and Cache Storage inspected before and after logout | T32 | FE-02, FE-03 | none | V3 |
| SO-32 | Security headers are present and the app is same-origin only; no CORS configuration is present | P2 | CSP includes `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`; no wildcard CORS or app-level HSTS | T33 | FE-04 | none | V14 |

### 4.6 Build and release

| ID | Objective | Pri | Success criteria | Threats | Tests | Events | ASVS / OWASP |
|---|---|---|---|---|---|---|---|
| SO-33 | The image contains no secrets and no default or seed credentials | P0 | `gitleaks`, `docker history` and env dump are clean; demo credential handling agreed (gap G9) | T35 | DP-01 | none | V14 |
| SO-34 | The container runs as non-root | P1 | `docker inspect` shows a non-root user | T36 | DP-02 | none | V14 |
| SO-35 | The submitted image is tagged (not `latest`), pinned, and has no known critical dependency vulnerabilities | P1 | Versioned tag; Trivy and `npm audit`: zero critical, highs triaged | T37 | DP-03 | none | V14 |
| SO-36 | The final image reports Healthy before the 13:00 submission on 13 Oct | P0 | `/healthz` and `/readyz` return 200 on the final tag | T38 | DP-04 | none | V14 |

## 5. Traceability summary

| Group | Objectives | P0 | P1 | P2 |
|---|---|---|---|---|
| Money integrity | SO-01 to SO-10 | 9 | 1 | 0 |
| Identity and access | SO-11 to SO-19, SO-37 | 6 | 4 | 0 |
| Transaction controls | SO-38 to SO-43 | 0 | 3 | 3 |
| Detection and evidence | SO-20 to SO-24, SO-44 | 2 | 3 | 1 |
| Input, platform, frontend | SO-25 to SO-32 | 1 | 5 | 2 |
| Build and release | SO-33 to SO-36 | 2 | 2 | 0 |
| **Total** | **44** | **20** | **18** | **6** |

## 6. Release gate

A build is GO for submission only when:
1. All P0 objectives pass, with no exceptions.
2. Every P1 failure is fixed or recorded in `docs/risk-register.md` with an owner, a reason and a compensating control.
3. The image is tagged, Healthy and has no embedded secrets.

If any P0 objective fails at 12:00 on 13 Oct, the Build Lead decides between a bug fix (allowed after freeze) and reverting to the last GO tag. Recommendation: revert if the fix cannot be retested by 12:45. The per-tag procedure is `docs/release-gate-checklist.md`.

## 7. Verification log

Record the result for each objective per tagged build: Met, Not met (with finding ID), Accepted risk, or Not tested. Copy a column for each additional tag.

| Field | v1 | v2 | v3 |
|---|---|---|---|
| Date and time (UTC) | | | |
| Tester | | | |
| P0 met (of 20) | | | |
| P1 met (of 18) | | | |
| P2 met (of 6) | | | |
| Failed objectives and finding IDs | | | |
| Not tested | | | |

Per-objective results are kept in `docs/security-test-plan.md`, section 4, and the evidence folders.

## 8. Evidence standard

- Each result links to a test run: script or test ID, build tag, timestamp.
- Failures include a reproduction (request, response, log lines) and are filed as findings with severity, steps and a suggested fix.
- Tests run against the tagged Healthy image only, never `latest`.

## 9. Compliance posture (fill after the final test run)

Objectives met: __ / 44. Partially met: __. Not met: __. Accepted risks: __ (see `docs/risk-register.md`).

## 10. Accepted residual risk

Entries are recorded in the acceptance record in `docs/risk-register.md`, each with the objective, failure description, reason accepted, compensating control, owner and expiry.

## 11. Out of scope

Physical security, host OS hardening, network-layer DDoS, and third-party payment rails (none in the contract).

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 12 Oct 2026 | Security Lead | Initial objectives (SO-01 to SO-36) |
| 1.0 | 12 Oct 2026 | Security Lead | Merged the two drafts; added success criteria, events and ASVS or OWASP references; added SO-37 to SO-44 for staff scope, fraud mapping, pending funds, deadlock, queue flood, collusion detection, UTC window and log persistence; SO-15 reworded to match the endpoint contract |
| 1.1 | 13 Oct 2026 | Security Lead | Corrected SO-20's required assertion count to 35 and aligned the logging requirements with the current JengaSec contract |
