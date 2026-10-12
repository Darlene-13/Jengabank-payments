# Setup notes (lead)

## pom.xml dependencies needed by these files
- spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-validation, spring-boot-starter-jdbc
- org.postgresql:postgresql (runtime)
- org.flywaydb:flyway-core AND org.flywaydb:flyway-database-postgresql
- org.springdoc:springdoc-openapi-starter-webmvc-api   (the -api artifact: no Swagger UI)
- net.logstash.logback:logstash-logback-encoder          (Dev 2's JSON logs)
- test: spring-boot-starter-test, org.testcontainers:postgresql, org.testcontainers:junit-jupiter
Java 21. Spring Boot 3.x, latest patch release.

## SecurityConfig must permit (no auth)
GET /healthz, /readyz, /openapi.json, /, /index.html, /assets/**, /icons/**, /sw.js,
/workbox-*.js, /manifest.webmanifest and the React routes; POST /api/v1/auth/login.

## Run locally
docker compose up -d db
DB_URL=jdbc:postgresql://localhost:5432/jengabank_payments DB_USER=jengabank DB_PASSWORD=local-dev-only mvn spring-boot:run
curl localhost:8080/healthz ; curl localhost:8080/readyz

## Release
git tag v1 && git push origin v1   -> CI tests, builds amd64, Trivy scans, pushes to GHCR.
Check the GHCR package is Private, create a classic token with read:packages only, submit the tag.

## What the database enforces by itself (tested on PostgreSQL 16)
- Every journal balances, checked at COMMIT (deferred constraint trigger).
- No customer account below zero, with a row lock: two parallel debits can't both pass.
- journals, ledger_entries, audit_log, reconciliation_runs: no UPDATE, DELETE or TRUNCATE.
- transfers: amounts/accounts/creator immutable; status only PENDING_APPROVAL -> COMPLETED|REJECTED,
  COMPLETED -> REVERSED. Never deleted.
- Approver can't be the creator (CHECK), on transfers and reversals.
- A journal can be reversed once (UNIQUE reversal_of); one live reversal per transfer.
- audit_log: app INSERTs (actor, action, target, details) only. The DB sets sequence, ts,
  prev_hash and hash. SELECT * FROM audit_verify() checks the whole chain.

## Notes for the devs
- Balance: SELECT balance_minor FROM account_balances WHERE account_id = ?
- Transfer: in ONE transaction, lock both accounts in id order with
  SELECT ... FROM accounts WHERE id IN (?, ?) ORDER BY id FOR NO KEY UPDATE,
  check balance, insert journal + both entries + transfer row + audit row.
- Always insert a journal's entries in the same transaction as the journal.
- Map DB errors: SQLSTATE 23514 (check_violation) -> 422, 23505 (unique) -> 409, 42501 -> 500 + alert.
- Users have one role column; the API returns it as roles: [role].
- Staff users have no accounts. Account 000000000001 is the bank's internal funding account.
