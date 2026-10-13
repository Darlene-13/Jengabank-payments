# JengaBank Payments: Submission Report

**JengaSec 2026 · APP04, Payment Processing Service**
**Written by:** Darlene Wendy, Build Lead
**Date:** 13 October 2026

---

## Summary

This report records what our team submitted to the JengaSec range for APP04, how the submitted image meets each range rule, and how we verified it before submission.

Our final submission is the container image `ghcr.io/darlene-13/jengabank-payments:v4`. It was built from `main` at commit `587c974`, passed the full test suite (16 unit tests and 50 integration tests), was rehearsed locally under the range's constraints, and is live on the team page. The image is private, runs as a non-root user on `linux/amd64`, and is about 109 MB.

---

## 1. What was submitted

Our submission has two parts: the container image, entered through the team-page form, and this report. The proposal (version 2) was submitted earlier, on 13 October at 09:00. The repository's `README.md` and the `docs/` folder support the live defence.

The form was completed with the following values:

| Field | Value |
|-------|-------|
| Image reference | `ghcr.io/darlene-13/jengabank-payments:v4` |
| Registry username | `Darlene-13` |
| Pull credential | A classic personal access token with the `read:packages` scope only, expiring after the competition |
| Port | `8080` |
| Health-check path | `/healthz` |
| Needs a database | Yes |
| Environment variables | None required |
| Extra images | None |

Final image record:

| | |
|---|---|
| Tag | `v4` |
| Digest | `sha256:1d6d1b8fb806dd3bd4225b44992bbc23ffb809afc106dd31aef20539927cd9da` |
| Source | `main` at `587c974` (Merge pull request #14) |
| Platform, user, size | `linux/amd64`, `10001:10001`, about 109 MB |

---

## 2. Compliance with the range rules

The image was designed against each JengaSec rule from the start, rather than adjusted at the end. The table below sets out each rule and how we meet it.

| # | Rule | Result | How it is met |
|---|------|--------|---------------|
| 1 | `linux/amd64` | Pass | The image is built with `docker buildx build --platform linux/amd64`. The build stages produce a platform-independent jar and static files, and the runtime stage targets the requested platform. |
| 2 | Non-root, no privileges | Pass | The application runs as UID/GID `10001` with no login shell. It needs no extra capabilities, host networking or host paths, and runs correctly with a read-only root filesystem. |
| 3 | One HTTP port | Pass | Port `8080` only. |
| 4 | Health path returns 200 when ready | Pass | `GET /healthz` returns `200 {"status":"UP"}`. The server only accepts connections once the application and its database migrations have fully started. A second endpoint, `GET /readyz`, also checks the database. |
| 5 | JSON logs on stdout | Pass | Every log line is a single JSON object (see section 5). |
| 6 | 2 CPU / 3 GB including the database; image under 2 GB | Pass | The image is about 109 MB on a JRE-only Alpine base. The Java heap is capped at 50% of container memory, and the JVM exits cleanly if it runs out of memory. |
| 7 | Creates its own tables; seeds when empty | Pass | Flyway applies migrations `V1` to `V8` on first start, creating the schema and loading fictional demo users, accounts and opening balances. Database cleaning is disabled, so data can't be wiped by the application. |
| 8 | No internet at runtime | Pass | The frontend is compiled into the jar, with no external fonts or scripts. The backend makes no outbound calls. |
| — | Private image | Pass | The GHCR package visibility is set to Private. |
| — | No real secrets or personal data | Pass | No secrets are included in the image. Seed users are fictional, and only BCrypt password hashes appear in the migrations. |

The application reads the database connection details that JengaSec injects (`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD`) directly. We deliberately don't use `DATABASE_URL`, because it isn't a JDBC URL and it embeds the password.

---

## 3. Verification before submission

Every release went through the same three checks before it was pushed.

**Automated tests.** `mvn verify` runs the unit tests and the integration tests against a real PostgreSQL 16 database (via Testcontainers). At the final commit, all 16 unit tests and 50 integration tests passed. The integration tests cover:
- transfers and idempotency
- limits and fraud rules
- maker-checker approvals and reversals
- ownership checks
- concurrent attempts to overdraw an account
- ledger integrity
- the database guards that refuse direct tampering
- the security log lines themselves

**Image inspection.** After each build we confirmed that the image is `linux/amd64`, that its configured user is `10001:10001`, and that it's well under 2 GB.

**Local rehearsal.** We then ran the image the way the range does:
- a separate PostgreSQL 16 container
- the `PG*` environment variables
- a read-only root filesystem
- all Linux capabilities dropped
- `no-new-privileges`
- limits of 1.5 CPUs and 2 GB of memory

In the rehearsal recorded for `v2`:
- `/healthz` returned `UP` and `/readyz` returned `READY`.
- The process ran as `uid=10001(app)`.
- At idle, the application used about 200 MiB of memory and the database about 50 MiB, comfortably within the 3 GB budget.

---

## 4. Release history

Each release was given a new tag; no tag was ever reused or overwritten.

| Tag | Changes |
|-----|---------|
| `v1` | First healthy submission. |
| `v2` | The complete feature set: double-entry ledger, transfers, maker-checker approvals, reversals, limits, fraud rules, hash-chained audit trail, end-of-day reconciliation and structured security logging. Digest `sha256:f32421a3f2c69ba394d5467f2d73beb7830aa0a89b07505d547417425b41437b`. |
| `v3` | Security events for rejected requests now always name the user, and role-based access denials are logged as `authz.access.denied`. The image build now caches Maven dependencies. |
| `v4` | A round-trip fraud rule, based on work by Lucy Njenga: if the account being paid sent the customer money in the last hour, the transfer is held for a checker instead of completing. |

---

## 5. Logging

Logging is how both the judges and our own defenders see what happens inside the application, so it was treated as a feature in its own right.

**Every HTTP request** produces one line containing all the fields the range expects:

```json
{"ts":"2026-10-13T08:40:39.616Z","logger_name":"http","requestId":"72a52fd4-…","src_ip":"10.60.2.14",
 "method":"POST","path":"/api/v1/transfers","status":201,"user":"c27250058","action":"transfer.completed","duration_ms":13}
```

- `ts` is in UTC.
- `src_ip` is the real peer address. Forwarded headers are only trusted from our own configured proxies, so attackers can't spoof their address.
- `user` is the signed-in username, or empty when nobody is signed in.
- `action` names the most serious security event of that request, or `http.request` if there was none.

**Every security decision** also produces its own line, linked to the request by the same `requestId`. Examples:
- a failed login
- a denied access attempt
- a blocked transfer
- a fraud review
- an idempotency conflict
- a rate limit

These events always carry the username when the request was signed in, including requests that were rejected. The full list of events is in `docs/log-actions.md`.

---

## 6. Credential handling

Two separate GitHub tokens are used, so that neither can do the other's job:

- **Push token** (`write:packages`): used only on the build laptop to push images. We logged out of the registry after every push, so the token doesn't stay stored on disk.
- **Pull token** (`read:packages` only): given to JengaSec through the submission form so the range can pull our private image.

Neither token was placed in chat, in a commit or in any file. If either one leaks, it will be revoked and replaced immediately.

---

## Conclusion

The submitted image, `v4`, meets every JengaSec rule and was tested and rehearsed under the range's constraints before submission. Its core design principle is that money safety is enforced twice: once in the application, and again in the database through constraints and triggers. Every security-relevant decision is visible in the logs. Together, these give the team a solid foundation for the live defence.

*Written by Darlene Wendy, Build Lead, JengaBank Payments.*
