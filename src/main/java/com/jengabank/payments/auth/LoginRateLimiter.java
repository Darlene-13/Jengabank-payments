package com.jengabank.payments.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Max N login attempts per IP per fixed 60s window, on top of per-account lockout.
 * Counted in the database (V6__login_rate_limits.sql), not in process memory, so the
 * limit holds even if requests from the same IP land on different app instances.
 */
@Component
public class LoginRateLimiter {

    private final JdbcTemplate jdbc;
    private final int maxPerMinute;

    public LoginRateLimiter(JdbcTemplate jdbc, @Value("${jengabank.auth.login-rate-per-minute:10}") int maxPerMinute) {
        this.jdbc = jdbc;
        this.maxPerMinute = maxPerMinute;
    }

    public boolean allow(String ip) {
        int count = jdbc.queryForObject("""
                INSERT INTO login_rate_limits (ip_address, window_start, attempt_count)
                VALUES (?, now(), 1)
                ON CONFLICT (ip_address) DO UPDATE SET
                    attempt_count = CASE WHEN login_rate_limits.window_start <= now() - INTERVAL '60 seconds'
                                         THEN 1
                                         ELSE login_rate_limits.attempt_count + 1 END,
                    window_start  = CASE WHEN login_rate_limits.window_start <= now() - INTERVAL '60 seconds'
                                         THEN now()
                                         ELSE login_rate_limits.window_start END
                RETURNING attempt_count
                """, Integer.class, ip);
        return count <= maxPerMinute;
    }
}
