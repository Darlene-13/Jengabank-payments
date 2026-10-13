package com.jengabank.payments.logging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic per-bucket rate limiter for expensive/search/export operations (organiser rule:
 * not just auth needs this). DB-backed, same atomic-UPDATE pattern as the login limiter and
 * the account lockout counter, so the limit holds across app instances and restarts.
 *
 * Usage: rateLimiter.allow("account.entries:" + userId, 30, 60) - max 30 calls per 60s for
 * that bucket. Pick a bucket_key that identifies both the operation and the subject being
 * limited, so different operations never share a counter by accident.
 *
 * Runs in its own transaction (REQUIRES_NEW), so the count is saved even if the caller's
 * transaction later rolls back. Windows must be 600 seconds or less: RateLimitCleanupJob
 * deletes rows older than 10 minutes.
 */
@Component
public class RateLimiter {

    private final JdbcTemplate jdbc;

    public RateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String bucketKey, int maxPerWindow, int windowSeconds) {
        int count = jdbc.queryForObject("""
                INSERT INTO rate_limits (bucket_key, window_start, attempt_count)
                VALUES (?, now(), 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempt_count = CASE WHEN rate_limits.window_start <= now() - (?::text || ' seconds')::interval
                                         THEN 1
                                         ELSE rate_limits.attempt_count + 1 END,
                    window_start  = CASE WHEN rate_limits.window_start <= now() - (?::text || ' seconds')::interval
                                         THEN now()
                                         ELSE rate_limits.window_start END
                RETURNING attempt_count
                """, Integer.class, bucketKey, windowSeconds, windowSeconds);
        return count <= maxPerWindow;
    }
}
