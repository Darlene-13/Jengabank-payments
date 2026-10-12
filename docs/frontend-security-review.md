# Frontend Security Review: Angular PWA

| Field | Value |
|---|---|
| Owner | Security Lead |
| Version | 1.1 |
| Date | 12 Oct 2026 |
| Status | Complete once per Healthy tag |
| References | OWASP ASVS 4.0.3 V3 (session), V5 (validation), V14 (configuration); Angular security guide |
| Related | `security-test-plan.md` (FE-01 to FE-04 are the test IDs for SO-30 to SO-32) |

Reviewer: ________  Tag: ________  Date (UTC): ________

## 1. Checks

| # | Check | How | SO | T | Result | Notes |
|---|---|---|---|---|---|---|
| FE-01 | No `innerHTML`, `bypassSecurityTrust*` or user-bound `[href]`; CSP present and restrictive; stored XSS payload renders as text | `grep -rE "innerHTML\|bypassSecurityTrust\|\[href\]" src/`; response headers; submit payload in free-text fields | SO-30 | T31 | | |
| FE-02 | Token storage location understood and justified (not `localStorage` unless a documented exception) | Inspect storage in dev tools | SO-31 | T32 | | |
| FE-03 | Service worker never caches authenticated `/api` responses | Check `ngsw-config.json`; go offline after login | SO-31 | T32 | | |
| FE-04 | CORS uses an explicit origin allowlist; security headers present (CSP, HSTS, `X-Content-Type-Options`, `X-Frame-Options` or `frame-ancestors`); `Cache-Control: no-store` on authenticated responses | Response headers on API and PWA | SO-32 | T33 | | |
| FE-05 | Cached data cleared on logout | Logout, inspect Cache Storage | SO-31 | T32 | | |
| FE-06 | Logout clears tokens and in-memory state | Inspect storage and state | SO-18 | T5 | | |
| FE-07 | Route guards are UX only; backend enforces | Call APIs directly with the wrong role | SO-13 | T8 | | |
| FE-08 | New Idempotency-Key for each new transfer, same key on retry | Network tab | SO-05 | T12 | | |
| FE-09 | Submit disabled while the request is in flight | Double-click test | SO-06 | T13 | | |
| FE-10 | 401 and 423 handled gracefully | Simulate responses | SO-17 | T1 | | |
| FE-11 | No secrets, API keys or internal URLs in the bundle | `grep` over `dist/` | SO-33 | T35 | | |
| FE-12 | Source maps disabled in the production build | Check `dist/*.map` | SO-33 | T35 | | |
| FE-13 | No sensitive data in URLs or browser history | Review routes | SO-21 | T6 | | |
| FE-14 | `npm audit` findings triaged | `npm audit --omit=dev` | SO-35 | T37 | | |
| FE-15 | Error messages do not leak backend details | Trigger errors | SO-27 | T29 | | |
| FE-16 | Amounts entered as integer minor units; no float arithmetic | Code review | SO-08 | T15 | | |
| FE-17 | Autocomplete and password field attributes sensible | Inspect | none | none | | |
| FE-18 | Third-party scripts and fonts use SRI or are self-hosted | Review `index.html` | SO-35 | T37 | | |
| FE-19 | PWA manifest and install behaviour contain no sensitive data | Review manifest | none | none | | |

## 2. Findings

| ID | Severity | Description | Fix owner |
|---|---|---|---|

Severity scale: see `release-gate-checklist.md`.

## Change log

| Version | Date | Change |
|---|---|---|
| 1.0 | 12 Oct 2026 | Initial checklist (20 checks) |
| 1.1 | 12 Oct 2026 | Aligned FE-01 to FE-04 with the test IDs in the Security Objectives; merged overlapping checks (19 total); added SO and T links |
