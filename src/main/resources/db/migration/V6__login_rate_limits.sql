-- Dev 2: per-IP login throttling must be stateless (organiser rule), so the
-- counter moves from an in-process map into the database, same pattern as
-- the per-account lockout counter on users.
CREATE TABLE login_rate_limits (
    ip_address     VARCHAR(45) PRIMARY KEY,   -- IPv4 or IPv6 text form
    window_start   TIMESTAMPTZ NOT NULL,
    attempt_count  INT         NOT NULL CHECK (attempt_count > 0)
);
