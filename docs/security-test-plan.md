# Security Test Plan: JengaBank Payments (APP04)

| Field            | Value                                                                                                                                                                                |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Owner            | Security Lead                                                                                                                                                                        |
| Version          | 1.2                                                                                                                                                                                  |
| Date             | 12 Oct 2026                                                                                                                                                                          |
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

## 2. Test cases

Columns: **SO** objective, **T** threat, **Event** expected from `log-actions.md`. "Variants" are the sub-cases to run under the same test ID.

### 2.1 AT: Authentication and session

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| AT-01 | Exceed the lockout threshold with wrong passwords. Variants: username case and whitespace variants still counted | 423 once locked; each failure counted | `auth.login.failed`, `auth.account.locked` | SO-17 | T1 | | |
| AT-02 | Login with wrong password versus unknown user. Compare status, body and timing over a sample | Identical 401 body; negligible timing difference | `auth.login.failed` | SO-17 | T2 | | |
| AT-03 | Lock a victim account deliberately; wait for expiry. Check per-account and per-IP limits | Lockout is time-boxed and expires; behaviour documented | `auth.account.locked` | SO-17 | T3 | | |
| AT-04 | Token validation. Variants: expired, tampered payload, tampered signature, `alg: none`, role claim edited to CHECKER, missing, malformed or duplicate Authorization header, token in query string | 401 every time; query-string token rejected | `auth.token.invalid` | SO-11 | T4 | | |
| AT-05 | Reuse token after logout. Variants: role change or user removal then reuse old token | Token unusable after logout (DB-backed session revocation) | `auth.logout` | SO-18 | T5 | | |

### 2.2 AZ: Authorization

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| AZ-01 | customerA requests customerB's account, entries and transfer by ID; transfer from a source account not owned; tamper pagination cursor to cross accounts | 404 with the same body as a nonexistent ID (403 only if policy says so, gap G10); no foreign data | `authz.access.denied` | SO-12 | T7 | | |
| AZ-02 | Role matrix: every role (and no token) against all 20 endpoints | Matches the endpoint contract; CUSTOMER to MAKER or CHECKER routes and MAKER to CHECKER routes return 403 | `authz.access.denied` | SO-13 | T8 | | |
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
| RV-04 | Reverse a reversal | Rejected | `request.invalid` | SO-09 | T17 | | |
| RV-05 | Reverse as CUSTOMER, CHECKER and unauthenticated | 403 (401 unauthenticated) | `authz.access.denied` | SO-15 | T18 | | |

### 2.4 LM, MC, FR: Limits, maker-checker, fraud

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| LM-01 | At, below and above the per-transfer limit | Correct status at each boundary | `limit.exceeded` | SO-10 | T19 | | |
| LM-02 | At, below and above the daily limit; run across UTC midnight (gap G7) | Correct status; reset at the documented UTC boundary | `limit.exceeded` | SO-10, SO-43 | T19, T43 | | |
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
| LG-01 | Trigger each event in `event-logging-verification-matrix.md` and check the log line | All 28 events fire with requestId, UTC ISO 8601 timestamp and actor | all | SO-20, SO-42 | T24, T42 | | |
| LG-02 | Search logs, error bodies and responses for passwords, tokens and full account details | None present | none | SO-21 | T6, T24 | | |
| LG-03 | CRLF and JSON-breaking characters in X-Request-Id, usernames and fields; 10 KB X-Request-Id; header echoed and logged | Log lines stay valid single-line JSON; oversize ID replaced or sanitised; header equals logged requestId | none | SO-21 | T24 | | |
| LG-04 | Write events, restart the container, read the log store | Events written before the restart are still available | none | SO-44 | T44 | | |

### 2.6 IN, IL, PG, RL: Input, platform, abuse

| ID | Procedure | Expected | Event | SO | T | Result | Evidence |
|---|---|---|---|---|---|---|---|
| IN-01 | SQLi payloads in path, query, body, cursor and headers; second-order via stored fields; code review that all queries are parameterised | No effect, clean error | `request.invalid` | SO-25 | T26 | | |
| IN-02 | Unknown fields and mass assignment (`status`, `userId`, `role`, `balance`, `id`, `createdAt`) | 400 | `request.invalid` | SO-26 | T27 | | |
| IN-03 | Malformed JSON, deep nesting, duplicate keys, oversize strings, unicode, null bytes, wrong Content-Type, empty body | 400 (415 for Content-Type, gap G3), standard error body | `request.invalid` | SO-26 | T27 | | |
| IN-04 | Body over 16 KB | 400 or 413 (gap G3), standard error body | `request.invalid` | SO-26 | T27 | | |
| IL-01 | Trigger errors: bad method, unknown route, malformed input, forced failure | `{code, message, requestId}` only; no stack trace, SQL or framework banner | `request.invalid` | SO-27 | T29 | | |
| IL-02 | Probe `/actuator/*`, `/h2-console`, `/swagger-ui`, `/env`, `/heapdump`, `/metrics`; review `/healthz`, `/readyz`, `/openapi.json` content | Not exposed (404 or 401); no versions, hosts or internals | none | SO-28 | T30 | | |
| PG-01 | limit 0, -1, 101, "abc", huge; no limit; malformed cursor | Default 50, max 100; invalid rejected with 400 | `request.invalid` | SO-29 | T34 | | |
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
| RV | 5 | | | | |
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
| **Total** | **60** | | | | |

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
| SO-09 | RV-01 to RV-04 | | SO-31 | FE-02, FE-03 | |
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
| F-001 | | | | | | Open | |

Severity scale and release effect are defined in `release-gate-checklist.md`.

## 6. Sign-off

Tester: ____________ Date: ________ Build Lead acknowledgement: ____________

## Appendix A: Ledger invariants

Run after every test run.

```sql
-- 1. Every journal balances
SELECT journal_id,
       SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END) AS net
FROM ledger_entries
GROUP BY journal_id
HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0;

-- 2. System-wide net is zero (expect 0 or NULL)
SELECT SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)
FROM ledger_entries;

-- 3. Stored balance equals entry-derived balance (if balances are stored)
SELECT a.id
FROM accounts a
LEFT JOIN (SELECT account_id,
                  SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END) AS s
           FROM ledger_entries GROUP BY account_id) e ON e.account_id = a.id
WHERE a.balance_minor <> COALESCE(e.s, 0);

-- 4. No negative balances (if policy forbids)
SELECT id FROM accounts WHERE balance_minor < 0;

-- 5. One transfer per idempotency key, scoped to user and endpoint (gap G2)
SELECT user_id, endpoint, idempotency_key, COUNT(*)
FROM transfers
GROUP BY user_id, endpoint, idempotency_key
HAVING COUNT(*) > 1;

-- 6. At most one reversal per transfer
SELECT original_transfer_id, COUNT(*)
FROM transfers
WHERE type = 'REVERSAL'
GROUP BY original_transfer_id
HAVING COUNT(*) > 1;

-- 7. No reversal of a reversal
SELECT r.id
FROM transfers r
JOIN transfers o ON r.original_transfer_id = o.id
WHERE r.type = 'REVERSAL' AND o.type = 'REVERSAL';
```

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
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": shared if same_key else str(uuid.uuid4()),
            }
            response = await client.post(f"{BASE}{path}", json=body, headers=headers)
            return response.status_code

        results = await asyncio.gather(*[fire() for _ in range(n)])
    print({status: results.count(status) for status in sorted(set(results))})
```

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 12 Oct 2026 | Initial test plan |
| 1.1 | 12 Oct 2026 | Re-keyed to the Threat Model and Security Objectives IDs; merged earlier cases as variants; added objective traceability, SQL invariants and race script |
| 1.2 | 12 Oct 2026 | Added LG-04; linked FR, AZ-04 and variant cases to the new objectives SO-37 to SO-44; total 60 tests |
