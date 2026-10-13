package com.jengabank.payments.logging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * login_rate_limits and rate_limits get a row per distinct IP/user seen, forever, unless
 * something prunes them - under a live attack with rotating/spoofed source IPs that's
 * unbounded growth. A row is only ever read or updated inside its own ~60s window, so
 * anything older than a few minutes is dead weight; deleting it changes no behaviour
 * (allow() just inserts a fresh row if the key comes back later).
 */
@Component
public class RateLimitCleanupJob {

    private final JdbcTemplate jdbc;

    public RateLimitCleanupJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void pruneStaleWindows() {
        jdbc.update("DELETE FROM login_rate_limits WHERE window_start < now() - INTERVAL '10 minutes'");
        jdbc.update("DELETE FROM rate_limits WHERE window_start < now() - INTERVAL '10 minutes'");
    }
}
