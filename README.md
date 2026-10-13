# JengaBank Payments

A payments service for Kenyan shilling transfers, built for **JengaSec 2026 (APP04, Payment Processing Service)**.

Customers send money between accounts. Every shilling moves through a double-entry ledger. Large or suspicious transfers wait for a second person to approve them. Every security decision is written as one JSON log line, so the defending team can see attacks as they happen.

The core rule: **money safety is enforced twice**, once in the application and again inside PostgreSQL. A bug in the Java code can't create or lose money, because the database refuses the write.

---

## Contents

1. [What it does](#1-what-it-does)
2. [Security controls](#2-security-controls)
3. [Architecture](#3-architecture)
4. [Running it locally](#4-running-it-locally)
5. [Tests](#5-tests)
6. [Logging](#6-logging)
7. [Container image](#7-container-image)
8. [Documentation map](#8-documentation-map)
9. [Team](#9-team)
10. [AI-use statement](#10-ai-use-statement)

---

## 1. What it does

| Role | Can do |
|------|--------|
| **CUSTOMER** | See their own accounts, balances and ledger statement, and send transfers |
| **MAKER** (bank operations) | Request a reversal of a completed transfer, read the audit trail and reconciliation results |
| **CHECKER** (bank operations) | Approve or reject held transfers and reversals, run reconciliation |

A transfer ends up in one of these states:

- **COMPLETED**: the money moved; the ledger has a balanced journal.
- **PENDING_APPROVAL**: held for a CHECKER, either because it's KES 250,000 or more or because a fraud rule asked for review. Nothing moves until a checker who isn't the creator approves it.
- **BLOCKED**: stopped by a limit or fraud rule. Kept as evidence, never deleted.
- **REJECTED**: a checker turned it down.
- **REVERSED**: undone later by a maker-checker reversal, which writes a new opposite journal. The original entries are never edited.

The full API (endpoints, error codes, lifecycles and an end-to-end demo script) is in [`docs/api.md`](docs/api.md).

---

## 2. Security controls

### Money and the ledger
- **Integer cents only.** Amounts are `amount_minor` (`BIGINT`); there are no floats anywhere.
- **Double entry.** Every journal must balance. A deferred constraint trigger checks this at `COMMIT`.
- **No overdrafts.** Accounts are locked in id order (`FOR NO KEY UPDATE`) before the balance check, and a database trigger rejects any customer balance below zero as a backstop.
- **Append-only history.** `journals`, `ledger_entries`, `audit_log` and `reconciliation_runs` refuse `UPDATE`, `DELETE` and `TRUNCATE`. On `transfers`, the amount, accounts and creator can't change. Status only moves `PENDING_APPROVAL → COMPLETED | REJECTED` or `COMPLETED → REVERSED`.
- **Hash-chained audit trail.** The database sets each audit row's sequence, timestamp and hash. `GET /api/v1/audit/verify`, which calls `audit_verify()`, walks the whole chain and reports the first broken link.
- **End-of-day reconciliation** runs at 23:00 Nairobi time, and on demand by a checker. It proves that ledger totals, balances and journals agree.

### Transfer controls
- **Idempotency.** `POST` endpoints that move money require an `Idempotency-Key`. Repeating the same key and body replays the original response. The same key with a different body returns `409`.
- **Limits:** KES 1,000,000 per transfer, KES 2,000,000 per customer per Nairobi calendar day, and at most 5 transfers per minute.
- **Maker-checker.** Transfers of KES 250,000 or more, and every reversal, need a CHECKER who isn't the creator. The database also enforces this with a `CHECK` constraint.
- **Fraud rules.** These run synchronously before anything is written. The worst decision wins:

| Rule | Decision |
|------|----------|
| 3 or more blocked attempts in the last 10 minutes | BLOCK |
| KES 100,000 or more to an account you have never paid | REVIEW |
| KES 50,000 or more between 00:00 and 05:00 Nairobi time | REVIEW |
| **Round trip:** the account you're paying sent you money in the last hour (a layering pattern) | REVIEW |

### Identity and access
- Passwords are stored as BCrypt hashes only (cost 12). After 5 failed logins an account is locked for 15 minutes, and logins are also rate-limited per client IP.
- Tokens are opaque random bearer values, stored server-side only as SHA-256 hashes. They expire after 30 minutes and are revoked on logout.
- Access is checked twice: Spring Security checks the role on each route, and every query that reads data is limited to the signed-in user's own records. Other users' resources return `404`, so their existence isn't revealed.
- Unknown destination account numbers are rate-limited (5 per 10 minutes), so account numbers can't be enumerated.

### Requests and errors
- 16 KB request size limit, strict JSON (unknown fields are rejected), and validation on every input.
- Errors are always `{ code, message, requestId }`. No stack traces, SQL or class names ever reach the client.
- Rate limits on login and on the expensive list endpoints.
- `X-Forwarded-For` is only trusted from configured proxies, so attackers can't fake their source IP in the logs.

---

## 3. Architecture

```
React (Vite) frontend ── built into the jar as static files, no CDN
        │
Spring Boot 3.5 / Java 21 ── auth · accounts · transfers · approvals · reversals
        │                     limits · fraud · audit · reconciliation · logging
        │
PostgreSQL 16 ── Flyway migrations V1–V8: schema, append-only triggers,
                 ledger balance checks, hash chain, seed data
```

| Path | What lives there |
|------|------------------|
| `src/main/java/com/jengabank/payments/` | One package per feature (`transfer`, `ledger`, `approval`, `reversal`, `auth`, `audit`, `reconciliation`, `logging`, …) |
| `src/main/resources/db/migration/` | Flyway migrations. Never edit one that has already run; add a new `V9__…` instead |
| `jenga-frontend/` | React + TypeScript frontend |
| `docs/` | Security and operations documentation (see [section 8](#8-documentation-map)) |
| `scripts/hash-password.sh` | Makes a BCrypt hash for a new or reset demo password |

---

## 4. Running it locally

You need Docker, Java 21 and Maven. The full stack runs with:

```bash
docker compose up --build
```

- The app is at http://localhost:8080. The database is published on `localhost:5433`, so it never clashes with a local PostgreSQL.
- Swagger UI is turned on for local use only; the image keeps it off.
- Compose runs the app the way the range does: amd64, read-only filesystem, `cap_drop: ALL`, `no-new-privileges`, 1.5 CPUs and 2 GB of memory.

To run the backend from your IDE or Maven instead:

```bash
docker compose up -d db
DB_URL=jdbc:postgresql://localhost:5433/jengabank_payments DB_USER=jengabank DB_PASSWORD=local-dev-only \
  mvn spring-boot:run
curl localhost:8080/healthz   # {"status":"UP"}
curl localhost:8080/readyz    # {"status":"READY"}
```

The seed data (migrations `V4` and `V8`) creates fictional demo users: customers `wanjiku` and `otieno`, maker `ops.maker`, and checkers `ops.checker` and `ops.checker2`. Their passwords are shared in the team chat only and are never in the repo.

---

## 5. Tests

```bash
mvn -B verify     # needs Docker running (Testcontainers starts a real PostgreSQL 16)
```

- **Unit tests:** the fraud engine and client-IP resolution.
- **Integration tests** against real PostgreSQL:
  - transfers and idempotency
  - limits and fraud rules
  - maker-checker approvals and reversals
  - ownership checks
  - concurrent transfers that try to overdraw an account
  - ledger integrity
  - the database guards refusing direct tampering
  - the security log lines themselves

CI (`.github/workflows/ci.yml`) runs on every push:

1. Backend tests and the frontend build.
2. On a version tag, it also builds the amd64 image, scans it with Trivy and fails on any CRITICAL finding.
3. It pushes the image to GHCR.

[`docs/ledger-invariants.sql`](docs/ledger-invariants.sql) contains SQL to check by hand that the ledger is consistent.

---

## 6. Logging

Each line on stdout is one JSON object.

**Every HTTP request** produces one `http` line:

```json
{"ts":"2026-10-13T08:40:39.616Z","logger_name":"http","requestId":"72a52fd4-…","src_ip":"10.60.2.14",
 "method":"POST","path":"/api/v1/transfers","status":201,"user":"wanjiku","action":"transfer.completed","duration_ms":13}
```

- Its `action` is the most severe security event of that request, or `http.request` if there was none.

**Every security decision** also gets its own `security` line, with the same `requestId`. Examples:
- `auth.login.failed`
- `authz.access.denied`
- `transfer.blocked`
- `fraud.review`
- `limit.exceeded`
- `idempotency.conflict`
- `rate.limited`
- `db.guard_triggered`

Events raised inside an authenticated request always carry the `username`, rejections included.

[`docs/log-actions.md`](docs/log-actions.md) lists every action and its fields. [`docs/event-logging-verification-matrix.md`](docs/event-logging-verification-matrix.md) shows how each one is triggered and checked.

---

## 7. Container image

`ghcr.io/darlene-13/jengabank-payments` (private).

| Requirement | How we meet it |
|-------------|----------------|
| Platform | `linux/amd64` |
| User | Non-root, UID/GID `10001`, no login shell, no extra capabilities |
| Port / health check | `8080` / `GET /healthz` (`/readyz` also checks the database) |
| Database | Uses the injected `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`; Flyway creates and seeds the schema on first start |
| Network | No internet needed at runtime; no outbound calls, no CDN |
| Size and memory | About 109 MB, JRE-only Alpine runtime. The heap is capped at 50% of container memory, and the JVM exits cleanly on out-of-memory |
| Filesystem | Runs with a read-only root filesystem (only `/tmp` is writable) |

Build:

```bash
docker buildx build --platform linux/amd64 -t ghcr.io/darlene-13/jengabank-payments:vN --load .
```

The build is three stages: React build, Maven build, then a slim JRE runtime. Maven dependencies are kept in a BuildKit cache, so rebuilds don't download them again.

---

## 8. Documentation map

| Document | What it covers |
|----------|----------------|
| [`docs/api.md`](docs/api.md) | Full API reference, error codes, lifecycles, demo script |
| [`docs/threat-model.md`](docs/threat-model.md) | Assets, attackers, threats, and the control for each one |
| [`docs/security-objectives.md`](docs/security-objectives.md) | What "secure" means for this service |
| [`docs/risk-register.md`](docs/risk-register.md) | Risks, likelihood, impact, mitigation and owner |
| [`docs/security-test-plan.md`](docs/security-test-plan.md) | Attack-by-attack tests, with the ledger invariant SQL in Appendix A |
| [`docs/ledger-invariants.sql`](docs/ledger-invariants.sql) | Read-only SQL that proves the ledger is consistent |
| [`docs/log-actions.md`](docs/log-actions.md) | Logging contract and every security event |
| [`docs/event-logging-verification-matrix.md`](docs/event-logging-verification-matrix.md) | How to trigger and verify each event |
| [`docs/frontend-security-review.md`](docs/frontend-security-review.md) | Frontend review: CSP, token handling, XSS |
| [`docs/live-defence-runbook.md`](docs/live-defence-runbook.md) | What we watch, how we detect, and how we respond during the live defence |

---

## 9. Team

| Role | Name | Owned |
|------|------|-------|
| Build Lead | Darlene Wendy | Schema and migrations, double-entry ledger, atomic transfers, idempotency, reversals, frontend, Docker and CI, code review |
| Developer, Identity and Visibility | Phenny Mwaisaka | Login, sessions and tokens, lockout, roles, account endpoints, structured request and security logging |
| Developer, Transaction Controls | Lucy Njenga | Limits, fraud engine (including the round-trip rule), maker-checker approvals, audit trail endpoints, end-of-day reconciliation |
| Security Lead | Christopher Mbuthia | Threat model, security objectives, attacking every release, frontend security review, live defence coordination |

---

## 10. AI-use statement

With the organisers' permission, and given the time constraints, the team used an AI assistant (Claude, by Anthropic) for planning, README documentation and boilerplate code. The team reviewed and validated the relevant outputs before submission.

---

Licensed under the [MIT License](LICENSE).
