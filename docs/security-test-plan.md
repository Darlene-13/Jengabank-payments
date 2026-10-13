# Security Test Plan: JengaBank Payments (APP04)

| Field            | Value                                                                                                                                                                                |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Owner            | Security Lead                                                                                                                                                                        |
| Version          | 1.3                                                                                                                                                                                  |
| Date             | 13 Oct 2026                                                                                                                                                                          |
| Related          | `threat-model.md`, `security-objectives.md`, `log-actions.md`, `event-logging-verification-matrix.md`, `frontend-security-review.md`, `risk-register.md`, `live-defence-runbook.md` |

## 1. Purpose and rules

Each test pairs an attack with expected **response** and **detection** (log event from `log-actions.md`). Test IDs map to threats (T1–T44) and objectives (SO-01–SO-44).

- Test only local/team environments against a tagged Healthy image (never `latest`).
- Destructive tests (audit tampering, injected imbalance) run on a database copy only.
- After each test, record response, log line, ledger effect, and run invariant SQL (Appendix A).
- Evidence: `evidence/<tag>/<test-id>/`.
- Result codes: **P** pass, **F** fail, **B** blocked/not testable, **N/A**.

| Field | Value |
|---|---|
| Tester | |
| Target tag | |
| Date/time (UTC) | |
| Base URL | |
| Test accounts | customerA, customerB, maker1, checker1, checker2 |

Execution results are intentionally blank until a named image has been tested. No image execution is recorded in this plan.

## 2. Test cases

Columns: **SO** objective, **T** threat, **Event** expected from `log-actions.md`. "Variants" are the sub-cases to run under the same test ID.

### 2.1 AT: Authentication and session

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| AT-01 | Exceed the lockout threshold with wrong passwords. Variants: username case and whitespace variants still counted | 423 once locked; each failure counted | `auth.login.failed`, `auth.account.locked` | SO-17 | T1 | | |
| AT-02 | Login with wrong password versus unknown user. Compare status, body and timing over a sample | Identical 401 body; negligible timing difference | `auth.login.failed` | SO-17 | T2 | | |
| AT-03 | Lock a victim account deliberately; wait for expiry. Check per-account and per-IP limits | Lockout is time-boxed and expires; behaviour documented | `auth.account.locked` | SO-17 | T3 | | |
| AT-04 | Token validation. Variants: random or modified token, revoked token, expired session, missing or malformed Authorization header, token in query string | 401 every time; query-string token rejected | `auth.token.invalid` | SO-11 | T4 | | |
| AT-05 | Reuse token after logout. Variants: role change or user removal then reuse old token | Token unusable after logout (DB-backed session revocation) | `auth.logout` | SO-18 | T5 | | |


### 2.2 AZ: Authorization

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| AZ-01 | customerA requests customerB's account, entries and transfer by ID; transfer from a source account not owned; tamper pagination cursor to cross accounts | 404 with the same body as a nonexistent ID (403 only if policy says so, gap G10); no foreign data | `authz.access.denied` | SO-12 | T7 | | |
| AZ-02 | Role matrix: every role (and no token) against all 23 endpoints in `docs/api.md` | Matches the endpoint contract; CUSTOMER to MAKER or CHECKER routes and MAKER to CHECKER routes return 403 | `authz.access.denied` | SO-13 | T8 | | |
| AZ-03 | Pass userId, role or identity in body, query and headers | Ignored; identity and role come from the token only | none | SO-14 | T9 | | |
| AZ-04 | Staff list and view all transfers; customer list and view own only | Staff scope matches the plan; customers see only their own | `authz.access.denied` on denials | SO-37 | T10 | | |

### 2.3 CC, ID, AM, LD, RV: Money integrity

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| CC-01 | Balance 1000, 20 parallel transfers of 100 (race script). Variants: opposing A to B and B to A repeated, no deadlock or 500 | At most 10 succeed; balance never negative; total ledger unchanged | `transfer.insufficient_funds` on rejects | SO-02, SO-03, SO-40 | T11, T40 | | |
| ID-01 | Money-changing POST with no key; with a non-UUID key | 400, no funds moved | `request.invalid` | SO-04 | T12 | | |
| ID-02 | Same key, same body | Original response returned | `idempotency.replayed` | SO-05 | T12 | | |
| ID-03 | Same key, same body: check the ledger | Funds moved exactly once, no second entry | `idempotency.replayed` | SO-05 | T12 | | |
| ID-04 | Same key, different body. Variants: key reuse after a 422 (gap G2); idempotency on approve, reject and reversal | 409; defined, consistent behaviour; enforced on all money-changing POSTs | `idempotency.conflict` | SO-05 | T12 | | |
| ID-05 | 20 parallel requests with the same key | Exactly one transfer | `idempotency.replayed` | SO-06 | T13 | | |
| ID-06 | Same key used by another user | No cross-user response; no effect on the other user's transfer | `idempotency.conflict` | SO-07 | T14 | | |
| AM-01 | amountMinor 0 and -1 | 400 or 422, no funds moved | `transfer.invalid_amount` | SO-08 | T15 | | |
| AM-02 | amountMinor Long.MAX_VALUE and MAX+1; credit overflowing destination balance | Rejected, no overflow | `transfer.invalid_amount` | SO-08 | T15 | | |
| AM-03 | amountMinor 1.5, "100", null, 1e3, array, object | 400 | `request.invalid` | SO-08 | T15 | | |
| AM-04 | Currency USD, "kes", empty, null, oversize | 400 or 422 | `request.invalid` | SO-08 | T15 | | |
| AM-05 | Same source and destination | 400 or 422 | `request.invalid` | SO-08 | T15 | | |
| AM-06 | Nonexistent or closed destination account | 404 or 422 | `request.invalid` | SO-08 | T15 | | |
| LD-01 | Run all invariant SQL after each test run (Appendix A) | Every query returns no rows; system net is zero | `reconciliation.failed` if broken | SO-01, SO-02 | T16 | | |
| RV-01 | Reverse the same transfer twice in sequence | Second reversal rejected | `reversal.duplicate_denied` | SO-09 | T17 | | |
| RV-02 | Two parallel reversals of one transfer | Exactly one succeeds | `reversal.duplicate_denied` | SO-09 | T17 | | |
| RV-03 | Reverse a transfer that is not COMPLETED (PENDING_APPROVAL, REJECTED, BLOCKED, REVERSED) | Rejected | `request.invalid` | SO-09 | T17 | | |
| RV-05 | Reverse as CUSTOMER, CHECKER and unauthenticated | 403 (401 unauthenticated) | `authz.access.denied` | SO-15 | T18 | | |

### 2.4 LM, MC, FR: Limits, maker-checker, fraud

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| LM-01 | At, below and above the per-transfer limit | Correct status at each boundary | `limit.exceeded` | SO-10 | T19 | | |
| LM-02 | At, below and above the daily limit; run across Nairobi midnight (UTC+3) | Correct status; reset at Nairobi midnight (21:00 UTC) | `limit.exceeded` | SO-10, SO-43 | T19, T43 | | |
| LM-03 | Velocity boundary; structuring just under limits; parallel requests passing limit checks together | Limits hold in aggregate; structuring caught by velocity or fraud | `limit.exceeded`, `fraud.review`, `fraud.block` | SO-10, SO-41 | T19, T41 | | |
| MC-01 | Checker approves a transfer they initiated | 403 or 422, no posting | `approval.self_approval_denied` | SO-15 | T20 | | |
| MC-02 | Approve twice; approve a rejected transfer; reject a completed one; unknown ID; replay an old approval | Rejected consistently; no second posting | `request.invalid` | SO-16 | T21 | | |
| MC-03 | Two checkers approve the same transfer; approve versus reject simultaneously. Variant: many pending transfers exceeding balance (gap G6) | One outcome, one posting; over-commit behaviour defined | `approval.approved` or `approval.rejected` once | SO-16, SO-39 | T21, T39 | | |
| FR-01 | Fraud ALLOW | 201 COMPLETED | `fraud.allow`, `transfer.completed` | SO-38 | T22 | | |
| FR-02 | Fraud REVIEW | 202 PENDING_APPROVAL | `fraud.review`, `transfer.pending_approval` | SO-38 | T22 | | |
| FR-03 | Fraud BLOCK | 422 BLOCKED, no funds moved | `fraud.block`, `transfer.blocked` | SO-38 | T22 | | |

### 2.5 AU, RC, LG: Audit, reconciliation, logging

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| AU-01 | Verify on clean data; then, on a DB copy, edit one audit row, delete another, and write concurrently | Clean passes; edit and delete fail verification; chain does not fork | `audit.chain.broken` | SO-22 | T23 | | |
| AU-02 | Check the app DB user's grants on the audit table; inspect audit entries for secrets | No UPDATE or DELETE grant; no secrets stored | none | SO-23 | T23 | | |
| RC-01 | Reconciliation clean run; run with an injected imbalance (DB copy); CUSTOMER and MAKER attempt a run | Clean passes; imbalance fails; non-CHECKER gets 403 | `reconciliation.passed`, `reconciliation.failed`, `authz.access.denied` | SO-24, SO-42 | T25, T42 | | |
| LG-01 | Trigger every event in `event-logging-verification-matrix.md`; assert the exact event and required fields in its JSON line | All 35 current `Actions.java` events are explicitly asserted with `requestId`, UTC `ts`, `src_ip`, `method`, `path`, `status`, `user`, and `action`; matrix records evidence | all | SO-20, SO-42 | T24, T42 | | |
| LG-02 | Search captured security logs for test passwords, issued tokens, signing keys, password hashes, full account numbers and raw request-body sentinels | None present | none | SO-21 | T6, T24 | | |
| LG-03 | CRLF and JSON-breaking characters in X-Request-Id and username fields; 10 KB X-Request-Id; compare response header to the structured event | Every log line parses as one JSON object; injected content remains a field value, not a forged event; oversized ID is replaced; correlation matches | none | SO-21 | T24 | | |
| LG-04 | Write events, restart the container, read the configured persistent log store | Events written before restart remain available | none | SO-44 | T44 | | |

### 2.6 IN, IL, PG, RL: Input, platform, abuse

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| IN-01 | SQLi payloads in path, query, body, cursor and headers; second-order via stored fields; code review that all queries are parameterised | No effect, clean error | `request.invalid` | SO-25 | T26 | | |
| IN-02 | Unknown fields and mass assignment (`status`, `userId`, `role`, `balance`, `id`, `createdAt`) | 400 | `request.invalid` | SO-26 | T27 | | |
| IN-03 | Malformed JSON, deep nesting, duplicate keys, oversize strings, unicode, null bytes, wrong Content-Type, empty body | 400 (415 for Content-Type, gap G3), standard error body | `request.invalid` | SO-26 | T27 | | |
| IN-04 | Body over 16 KB | 400 or 413 (gap G3), standard error body | `request.invalid` | SO-26 | T27 | | |
| IL-01 | Trigger errors: bad method, unknown route, malformed input, forced failure | `{code, message, requestId}` only; no stack trace, SQL or framework banner | `request.invalid` | SO-27 | T29 | | |
| IL-02 | Probe `/actuator/*`, `/h2-console`, `/swagger-ui`, `/env`, `/heapdump`, `/metrics`; review `/healthz`, `/readyz`, `/openapi.json` content | `/swagger-ui` returns 404 in the deployed image; `/openapi.json` remains publicly accessible by team decision; no versions, hosts or internals | none | SO-28 | T30 | | |
| PG-01 | limit 0, -1, 101, "abc", integer overflow; no limit; malformed cursor | Default 50; 1 and 100 accepted; values below 1, above 100, non-integer/overflow and malformed cursor rejected with 400 | `request.invalid` | SO-29 | T34 | | |
| RL-01 | Burst login attempts and burst transfers | 429 | `rate.limited` | SO-19, SO-41 | T1, T28, T41 | | |

### 2.7 FE and DP: Frontend and deployment

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| FE-01 to FE-04 | Run the matching checks in `frontend-security-review.md` | See that document | none | SO-30 to SO-32 | T31 to T33 | | |
| DP-01 | Secret scan of repo, history and image; check seed and demo credentials in the image (gap G9) | No secrets, no default credentials | none | SO-33 | T35 | | |
| DP-02 | `docker inspect` the running user | Non-root | none | SO-34 | T36 | | |
| DP-03 | Tag is versioned and pinned; `trivy image`, `npm audit --omit=dev` | Not `latest`; no known critical vulnerabilities, highs triaged | none | SO-35 | T37 | | |
| DP-04 | `/healthz` and `/readyz` on the final tag before 13:00, 13 Oct | Both 200 | none | SO-36 | T38 | | |

## 3. Result summary (per tag)

| Category | Total | Pass | Fail | Blocked | Notes |
|---|---|---|---|---|---|
| AT | 5 | | | | |
| AZ | 4 | | | | |
| CC | 1 | | | | |
| ID | 6 | | | | |
| AM | 6 | | | | |
| LD | 1 | | | | |
| RV | 4 | | | | |
| LM | 3 | | | | |
| MC | 3 | | | | |
| FR | 3 | | | | |
| AU | 2 | | | | |
| RC | 1 | | | | |
| LG | 4 | | | | |
| IN | 4 | | | | |
| IL | 2 | | | | |
| PG | 1 | | | | |
| RL | 1 | | | | |
| FE | 4 | | | | |
| DP | 4 | | | | |
| **Total** | **59** | | | | |

## 4. Objective traceability

Results per objective come from the tests listed. An objective is met only when every linked test passes on a Healthy tag. Priorities and success criteria are in `security-objectives.md`.

| SO | Tests | Result | SO | Tests | Result |
|---|---|---|---|---|---|
| SO-01 | LD-01 | | SO-23 | AU-02 | |
| SO-02 | LD-01, CC-01 | | SO-24 | RC-01 | |
| SO-03 | CC-01 | | SO-25 | IN-01 | |
| SO-04 | ID-01 | | SO-26 | IN-02, IN-03, IN-04 | |
| SO-05 | ID-02, ID-03, ID-04 | | SO-27 | IL-01 | |
| SO-06 | ID-05 | | SO-28 | IL-02 | |
| SO-07 | ID-06 | | SO-29 | PG-01 | |
| SO-08 | AM-01 to AM-06 | | SO-30 | FE-01 | |
| SO-09 | RV-01 to RV-03 | | SO-31 | FE-02, FE-03 | |
| SO-10 | LM-01 to LM-03 | | SO-32 | FE-04 | |
| SO-11 | AT-04 | | SO-33 | DP-01 | |
| SO-12 | AZ-01 | | SO-34 | DP-02 | |
| SO-13 | AZ-02 | | SO-35 | DP-03 | |
| SO-14 | AZ-03 | | SO-36 | DP-04 | |
| SO-15 | MC-01, RV-05 | | SO-37 | AZ-04 | |
| SO-16 | MC-02, MC-03 | | SO-38 | FR-01 to FR-03 | |
| SO-17 | AT-01, AT-02, AT-03 | | SO-39 | MC-03 | |
| SO-18 | AT-05 | | SO-40 | CC-01 | |
| SO-19 | RL-01 | | SO-41 | RL-01, LM-03 | |
| SO-20 | LG-01 | | SO-42 | LG-01, RC-01 | |
| SO-21 | LG-02, LG-03 | | SO-43 | LM-02 | |
| SO-22 | AU-01 | | SO-44 | LG-04 | |

Every objective has at least one test, and every test links to at least one objective.

If the Build Lead confirms that a user can hold both CUSTOMER and MAKER roles, add a case to RV-05 where that user reverses their own transfer (SO-15).

## 5. Findings register

| ID | Title | Severity | Endpoint / area | Repro | Owner | Status | Retest tag |
|---|---|---|---|---|---|---|---|

No test findings have been recorded. Add findings here after execution; use Critical, High, Medium or Low for severity.

## 6. Sign-off

Tester: ____________ Date: ________ Build Lead acknowledgement: ____________

## Appendix A: Ledger invariants

Run after every test run. The canonical, executable copy is `docs/ledger-invariants.sql`; the short checks below match the same schema and intent.

```sql
-- 1. Every journal balances.
SELECT journal_id,
       SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net
FROM ledger_entries
GROUP BY journal_id
HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0;

-- 2. System-wide net is zero.
SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0) AS system_net
FROM ledger_entries;

-- 3. Balances are derived, never stored: account_balances must equal the sum of entries.
SELECT b.account_id
FROM account_balances b
LEFT JOIN (
    SELECT account_id,
           SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END) AS s
    FROM ledger_entries
    GROUP BY account_id
) e ON e.account_id = b.account_id
WHERE b.balance_minor <> COALESCE(e.s, 0);

-- 4. No negative balance except the bank's funding account.
SELECT a.id, a.account_number, b.balance_minor
FROM accounts a
JOIN account_balances b ON b.account_id = a.id
WHERE b.balance_minor < 0 AND NOT a.allow_negative;

-- 5. One stored result per idempotency key, scoped to user (primary key enforces this).
SELECT user_id, idem_key, COUNT(*)
FROM idempotency_keys
GROUP BY user_id, idem_key
HAVING COUNT(*) > 1;

-- 6. At most one live reversal per transfer.
SELECT transfer_id, COUNT(*)
FROM reversals
WHERE status <> 'REJECTED'
GROUP BY transfer_id
HAVING COUNT(*) > 1;

-- 7. Each journal reversed at most once, and never a reversal of a reversal.
SELECT r.id
FROM journals r
JOIN journals o ON o.id = r.reversal_of
WHERE r.kind = 'REVERSAL' AND o.kind = 'REVERSAL';

-- 8. Transfer status matches the ledger: COMPLETED/REVERSED have a journal; REVERSED has a reversal journal.
SELECT id, status
FROM transfers
WHERE (status IN ('COMPLETED', 'REVERSED') AND journal_id IS NULL)
   OR (status = 'REVERSED' AND reversal_journal_id IS NULL)
   OR (status IN ('PENDING_APPROVAL', 'REJECTED', 'BLOCKED') AND journal_id IS NOT NULL);
```

The database primary key on `idempotency_keys(user_id, idem_key)` enforces key uniqueness. Replays, mismatches, cross-user isolation and concurrency are verified through ID-02–ID-06 rather than querying nonexistent idempotency columns on `transfers`. Reversals are journal records linked by `journals.reversal_of`, and the database enforces a unique one-reversal-per-journal rule.

## Appendix B: Race script template

```python
import asyncio
import uuid
import httpx

BASE = "http://localhost:8080/api/v1"

async def race(token, path, body, n=20, same_key=False):
    """Fire n parallel POSTs; print a count of status codes."""
    shared = str(uuid.uuid4())
    async with httpx.AsyncClient(timeout=15) as client:
        async def fire():
            headers = {
                "Authorization": "Bearer " + token,
                "Idempotency-Key": shared if same_key else str(uuid.uuid4()),
            }
            response = await client.post(f"{BASE}{path}", json=body, headers=headers)
            return response.status_code

        results = await asyncio.gather(*[fire() for _ in range(n)])
    print({status: results.count(status) for status in sorted(set(results))})
```

## Appendix C: JengaSec submission readiness

This is a source/configuration review, not a pass for a submitted image. Image-specific checks require the exact registry reference and digest.

| Requirement | Repository evidence | Review status |
|---|---|---|
| Build for `linux/amd64` | `Dockerfile` runtime stage, `.github/workflows/ci.yml` tag build, and `docker-compose.yml` app build specify `linux/amd64`. | Configured. Submitted image architecture and digest not inspected. |
| Run as non-root | Runtime image uses `USER 10001:10001`; local Compose drops capabilities and enables `no-new-privileges`. | Configured. Submitted container user not inspected. |
| One HTTP port | `server.port` defaults to 8080; the image exposes 8080 and local Compose publishes it. | Configured for port 8080. Confirm the submission form uses 8080. |
| Health check | `/healthz` returns 200 when the process is up; the image health check probes it. `/readyz` checks database readiness. | Configured. Healthy response from submitted image not recorded. |
| Request logs on stdout | `logback-spring.xml` uses a console appender; `RequestLogFilter` emits request, source IP, status, user and action fields. | Configured. Only 12/35 event names have direct test assertions; compare emitted fields with the complete JengaSec contract before sign-off. |
| CPU and memory | Local Compose limits the app to 1.5 CPUs and 2 GB, but sets no resource limit for PostgreSQL. | Not verified for the range. The app and database together must stay within 2 CPUs and 3 GB. |
| Image size below 2 GB | No submitted image size or digest is recorded in this repository. | Pending image inspection. |
| Database migrations and seed data | Flyway runs at startup; migrations create the schema and include made-up seed records. | Configured. Startup against a fresh database has not been recorded as a submission check. |
| No runtime internet dependency | No egress-blocked deployment result is recorded. | Pending an isolated-network startup and smoke test. |
| Private image and read-only pull token | CI pushes version tags to GHCR and reminds the publisher to set package visibility to private. Registry visibility and the submission token are external to this repository. | Pending confirmation on the package and submission form. |
| Versioned image reference | CI builds and pushes `v*` tags. The image reference submitted on the team page is not recorded here. | Record the exact tag and digest for the passing submission; do not use `latest`. |
| Environment configuration | `application.yml` accepts injected `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD`; local Compose uses `DB_*`. | Configured for the documented PostgreSQL variables. Confirm the final range environment. |

At review time, the remote Git repository had a `v1` tag and no `v2` Git tag. This does not establish whether a separate `v2` image exists in the registry. `docker ps` listed no running containers during this review.

The source baseline includes `origin/main` commit `98e157a`: approval and reversal flows were moved onto the shared ledger and idempotency APIs, and `ApprovalAndReversalIT` was updated for the resulting response states and Nairobi daily-limit boundary. These code changes have not been executed against a submitted image as part of this review.

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 12 Oct 2026 | Initial test plan |
| 1.1 | 12 Oct 2026 | Re-keyed to the Threat Model and Security Objectives IDs; merged earlier cases as variants; added objective traceability, SQL invariants and race script |
| 1.2 | 12 Oct 2026 | Added LG-04; linked FR, AZ-04 and variant cases to the new objectives SO-37 to SO-44; total 60 tests |
| 1.3 | 13 Oct 2026 | Tightened pagination boundaries and logging criteria; documented automated evidence limits and known implementation gaps |
| 1.4 | 13 Oct 2026 | Added JengaSec submission checks, recorded the `origin/main` baseline, and clarified pending image-level verification |
