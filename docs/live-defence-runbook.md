# Live Defence Runbook: JengaBank Payments (APP04)

| Field        | Value                                                         |
| ------------ | ------------------------------------------------------------- |
| Owner        | Security Lead                                                 |
| Version      | 1.2                                                           |
| Date         | 13 Oct 2026                                                   |
| Related      | `security-test-plan.md`, `risk-register.md`, `log-actions.md` |

## 1. Key dates

| When | Item | Owner |
|---|---|---|
| Tue 13 Oct, 12:00 PM | Code freeze; bug fixes only after | All |
| Tue 13 Oct, 12:00–13:00 | Final regression on candidate tag; confirm Healthy, not `latest` | Security Lead |
| Tue 13 Oct, 13:00 | Final image submitted and Healthy | Build Lead |
| Wed 14–Fri 16 Oct | Live defence at Strathmore; bring laptop and ID | All |

## 2. Logistics checklist

- [ ] Laptop, charger, adapters, ID
- [ ] Local copy of final image and previous tag
- [ ] Evidence pack
- [ ] Credentials in password manager (not in repo/file)
- [ ] Phone hotspot as fallback
- [ ] Cheat sheet
- [ ] Arrive 30 min early; test display

## 3. Roles

| Topic | Responsible | Accountable |
|---|---|---|
| Overall defence, Q&A | Security Lead | Build Lead |
| Ledger, transfers, concurrency, idempotency, reversal | Build Lead | Build Lead |
| Auth, accounts, logging | Dev 2 | Build Lead |
| Limits, fraud, approval, audit, reconciliation | Dev 3 | Build Lead |
| Threat model/security objectives | Security Lead | Security Lead |
| Frontend | Build Lead | Build Lead |
| Live demo operator | TBD | Security Lead |

## 4. Startup and recovery

```bash
# Start
# The app listens on :8080; the database is provisioned from PG* / DB_* env vars.
docker compose up -d
curl -s localhost:8080/healthz
curl -s localhost:8080/readyz

# Roll back to the previous tag
docker compose down
IMAGE_TAG=<previous-tag> docker compose up -d

# Logs (first 50 lines)
docker compose logs app | head -n 50

# Reset demo data from a known snapshot
# Keep a known-good SQL snapshot in the evidence folder and restore it to the local DB.
docker exec -i $(docker compose ps -q db) psql -U jengabank -d jengabank_payments < ./evidence/<tag>/db-snapshot.sql
```

Healthy expectation: `/healthz` and `/readyz` return 200 within 30 seconds.

## 5. Demo script

| Min | Step | Shows | Evidence |
|---|---|---|---|
| 0-1 | Architecture & roles | Scope | Diagram |
| 1-2 | Login, view account, logout (if shown) | Auth, ownership | `auth.login.success`, `account.viewed`, `auth.logout` |
| 2-3 | Transfer succeeds | Double-entry, 201 | `transfer.completed` |
| 3-4 | Replay Idempotency-Key | Exactly once | `idempotency.replayed` |
| 4-5 | Cross-user access denied | Ownership 404 | `authz.access.denied` |
| 5-6 | Limit/risky transfer | Limits, fraud | `limit.exceeded`, `fraud.review`, `fraud.block` |
| 6-7 | Approvals | Maker-checker | `approval.approved`, `approval.self_approval_denied` |
| 7-8 | Parallel transfers | No double spend | Invariant SQL clean |
| 8-9 | Audit verify | Tamper detection | `audit.chain.ok`, `audit.chain.broken` |
| 9-10 | Reconciliation | Integrity | `reconciliation.passed` |

Rehearse twice. Keep a recorded backup video of each step.

## 6. Demo data

| Role | Username | Purpose |
|---|---|---|
| CUSTOMER | `wanjiku` | Own accounts, transfers |
| CUSTOMER | `otieno` | Second user for the IDOR demo |
| MAKER | `ops.maker` | Reversals |
| CHECKER | `ops.checker` | Approvals, reconciliation |

Passwords live in the password manager only.

## 7. Evidence pack

One folder per tag: `evidence/<tag>/<test-id>/`. The index is the objective traceability table in `security-test-plan.md`, section 4, with results filled from each tag's runs. Keep each tag's completed gate sheet and findings alongside it.

## 8. Anticipated questions

| Question | Owner | Prepared answer |
|---|---|---|
| How do you prevent double spend? | Build Lead | Double-entry ledger plus database constraints; every transfer is posted as one atomic journal, the balance is re-checked, and the database forbids overdrafts and duplicate reversal journals. |
| How is idempotency scoped? | Build Lead | Keys are scoped per user and endpoint, and the server hashes METHOD + path + JSON body. The same key and same payload replays the original response; a different payload returns `409 IDEMPOTENCY_CONFLICT`. |
| How do you detect audit tampering? | Dev 3 | The application owns the tables and triggers block direct audit-row mutation; a hash chain with `sequence`, `prev_hash` and `hash` is also written so tampering that bypasses the trigger is detectable during verification. |
| How are passwords stored and lockout handled? | Dev 2 | Passwords are stored as bcrypt hashes only (migration V8), and after five failed logins the account is locked for 15 minutes. Requests are also rate limited per client IP. |
| What happens when a token is stolen? | Dev 2 | Tokens are opaque random bearer values, stored only as SHA-256 hashes in the server-side `sessions` table. They expire after 30 minutes and are revoked on logout, so a stolen token is only useful until expiry or revocation. |
| Why can a MAKER reverse without a checker? | Security Lead | Entry in `risk-register.md` (RR-04) |
| What would you do with more time? | Security Lead | Section 9 |

## 9. Known limitations and accepted risks

| Risk | Limitation                                               | Impact                                                        | Mitigation                                                                                                   | Approved by   |
| ---- | -------------------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ | ------------- |
| G5   | Maker-only reversal below KES 250,000                    | Potential fraud by a single maker                             | Reversal threshold is enforced for KES 250,000+; below that, maker can reverse and every reversal is audited | Security Lead |
| G9   | Demo credentials are seeded and shared only in team chat | Potential credential reuse outside the controlled environment | Random 20-character passwords are bcrypt-hashed in V8; plaintext remains confined to the team chat only      | Security Lead |

## 10. Incident drill

| Phase | Action |
|---|---|
| Detect | Watch for bursts of `auth.login.failed`, `rate.limited`, `audit.chain.broken`, `reconciliation.failed`, `idempotency.conflict` |
| Analyse | Identify actor, IP and time window by `requestId`; run the invariant SQL |
| Contain | Lock affected accounts, rotate the signing key, disable the affected endpoint or roll back the tag |
| Recover | Restore from snapshot, re-run reconciliation and audit verify |
| Report | Timeline, root cause, impact, fix, owner |

Scenarios to rehearse: credential-stuffing burst, ledger mismatch, audit break, failed deploy needing rollback, a judge asking for a live attack.

## 11. Live attack protocol

1. Confirm scope/permission with judges.
2. Snapshot DB before destructive actions.
3. Narrate expected response and event before executing.
4. Show log line and invariant check after.
5. Restore snapshot.

## 12. After the session

- [ ] Record questions asked and answers given
- [ ] Log new findings in the register
- [ ] Reset the environment for the next session

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 12 Oct 2026 | Initial runbook |
| 1.1 | 12 Oct 2026 | Added key dates from the playbook; linked demo steps to test IDs; replaced the evidence table with a pointer to the objective traceability |
| 1.2 | 13 Oct 2026 | Filled the demo accounts, recovery command, and pre-prepared Q&A from the code-backed implementation and risk register |
