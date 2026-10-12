# Live Defence Runbook: JengaBank Payments (APP04)

| Field        | Value                                                         |
| ------------ | ------------------------------------------------------------- |
| Owner        | Security Lead                                                 |
| Version      | 1.1                                                           |
| Date         | 12 Oct 2026                                                   |
| Status       | Complete bracketed fields after freeze                        |
| Event        | JengaSec 2026 live defence, Strathmore, 14 to 16 Oct          |
| Final tag    |                                                               |
| Image digest |                                                               |
| Format       | Operational runbook with RACI; NIST SP 800-61 incident phases |
| Related      | `security-test-plan.md`, `risk-register.md`, `log-actions.md` |

## 1. Key dates

| When | Item | Owner |
|---|---|---|
| Mon 12 Oct, 11:59 PM | Profile, ID upload, Code of Conduct | Everyone |
| Tue 13 Oct, 12:00 PM | Code freeze; bug fixes only after | All |
| Tue 13 Oct, 12:00 to 1:00 PM | Final regression on the candidate tag; confirm Healthy, not `latest` | Security Lead |
| Tue 13 Oct, 1:00 PM | Final image submitted and Healthy | Build Lead |
| Tue 13 Oct, 2:00 PM | Official freeze; runbook and evidence pack locked | All |
| 14 to 16 Oct | Live defence at Strathmore; bring laptop and ID | All |

## 2. Logistics checklist

- [ ] Laptop, charger, adapters, ID
- [ ] Local copy of the final image and the previous tag (works offline)
- [ ] Evidence pack stored locally and on USB (no Wi-Fi dependency)
- [ ] Credentials in a password manager, not in the repo or this file
- [ ] Phone hotspot as fallback
- [ ] Printed one-page cheat sheet of commands and roles
- [ ] Arrive 30 minutes early; test projector and display

## 3. Roles (RACI)

| Topic | Responsible | Accountable | Consulted |
|---|---|---|---|
| Overall defence, Q&A routing | Security Lead | Build Lead | All |
| Ledger, transfers, concurrency, idempotency, reversal | Build Lead | Build Lead | Security Lead |
| Auth, accounts, logging | Dev 2 | Build Lead | Security Lead |
| Limits, fraud, approval, audit, reconciliation | Dev 3 | Build Lead | Security Lead |
| Threat model and security objectives | Security Lead | Security Lead | Team |
| Frontend | Build Lead | Build Lead | Security Lead |
| Live demo operator | [name] | Security Lead | |

## 4. Startup and recovery

```bash
# Start 
docker compose up -d
curl -s localhost:8080/healthz
curl -s localhost:8080/readyz

# Roll back to the previous tag
docker compose down
IMAGE_TAG=v__ docker compose up -d

# Logs (first 50 lines)
docker compose logs app | head -n 50

# Reset demo data from a known snapshot
[fill: restore command]
```

Healthy expectation: `/healthz` and `/readyz` return 200 within [__] seconds.

## 5. Demo script (8 to 10 minutes)

| Min | Step | Shows | Evidence (events and tests) |
|---|---|---|---|
| 0-1 | Introduce architecture and roles | Scope | Diagram |
| 1-2 | Customer logs in, views account | Authentication, ownership | `auth.login.success`, `account.viewed` |
| 2-3 | Transfer succeeds | Double-entry, 201 | `transfer.completed` |
| 3-4 | Replay the same Idempotency-Key | Exactly once (ID-02, ID-03) | `idempotency.replayed` |
| 4-5 | Customer A requests B's account | Ownership returns 404 (AZ-01) | `authz.access.denied` |
| 5-6 | Over-limit or risky transfer | Limits, fraud, 422 or 202 (LM, FR) | `limit.exceeded`, `fraud.review`, `fraud.block` |
| 6-7 | Checker approves; try self-approval | Maker-checker (MC-01) | `approval.approved`, `approval.self_approval_denied` |
| 7-8 | Parallel transfer script | No double spend (CC-01) | Invariant SQL clean |
| 8-9 | Audit verify, then tampered DB copy | Tamper evidence (AU-01) | `audit.chain.broken` |
| 9-10 | Reconciliation | Integrity (RC-01) | `reconciliation.passed` |

Rehearse twice. Keep a recorded backup video of each step.

## 6. Demo data

| Role | Username | Purpose |
|---|---|---|
| CUSTOMER | [fill] | Own accounts, transfers |
| CUSTOMER | [fill] | Second user for the IDOR demo |
| MAKER | [fill] | Reversals |
| CHECKER | [fill] | Approvals, reconciliation |

Passwords live in the password manager only.

## 7. Evidence pack

One folder per tag: `evidence/<tag>/<test-id>/`. The index is the objective traceability table in `security-test-plan.md`, section 4, with results filled from each tag's runs. Keep each tag's completed gate sheet and findings alongside it.

## 8. Anticipated questions

| Question | Owner | Prepared answer |
|---|---|---|
| How do you prevent double spend? | Build Lead | [fill] |
| How is idempotency scoped? | Build Lead | [fill] |
| How do you detect audit tampering? | Dev 3 | [fill] |
| How are passwords stored and lockout handled? | Dev 2 | [fill] |
| What happens when a token is stolen? | Dev 2 | [fill] |
| Why can a MAKER reverse without a checker? | Security Lead | Entry in `risk-register.md` (RR-04) |
| What would you do with more time? | Security Lead | Section 9 |

## 9. Known limitations and accepted risks

State them honestly; do not overclaim. Copy each entry from the acceptance record in `risk-register.md`.

| Risk | Limitation | Impact | Mitigation | Approved by |
|---|---|---|---|---|
| [fill] | | | | |

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

1. Confirm scope and permission with the judges.
2. Snapshot the DB before any destructive action.
3. Narrate the expected response and expected log event before executing.
4. Show the log line and the invariant check afterwards.
5. Restore the snapshot.

## 12. After the session

- [ ] Record questions asked and answers given
- [ ] Log new findings in the register
- [ ] Reset the environment for the next session

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 12 Oct 2026 | Initial runbook |
| 1.1 | 12 Oct 2026 | Added key dates from the playbook; linked demo steps to test IDs; replaced the evidence table with a pointer to the objective traceability |
