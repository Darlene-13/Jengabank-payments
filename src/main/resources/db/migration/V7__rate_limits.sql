-- Dev 2: organiser rule 7 requires rate limiting on search/export/expensive operations,
-- not just auth. Generic per-bucket counter so any such endpoint can reuse it (bucket_key
-- is "<operation>:<subject>", e.g. "account.entries:<userId>") - same stateless, DB-backed,
-- atomic-UPDATE pattern as login_rate_limits.
CREATE TABLE rate_limits (
    bucket_key     VARCHAR(160) PRIMARY KEY,
    window_start   TIMESTAMPTZ NOT NULL,
    attempt_count  INT         NOT NULL CHECK (attempt_count > 0)
);
