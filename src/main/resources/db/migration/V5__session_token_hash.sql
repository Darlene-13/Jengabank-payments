-- Tokens are random and opaque. We store only their SHA-256, so a database leak
-- doesn't hand out working sessions.
ALTER TABLE sessions ADD COLUMN token_hash CHAR(64) NOT NULL UNIQUE;
