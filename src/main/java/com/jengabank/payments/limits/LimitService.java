package com.jengabank.payments.limits;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jengabank.payments.config.JengaProperties;

/**
 * Server-side transaction limits, per customer.
 *
 * <p>Defence against limit bypass by splitting: the daily check never looks at the
 * current transfer alone. It adds it to everything the customer sent in the last
 * 24 hours (rolling, so midnight doesn't reset it). PENDING_APPROVAL counts, since
 * queued money is still committed money; BLOCKED and REJECTED don't.
 */
@Service
public class LimitService {

    /** What the customer has done recently. Also feeds the fraud rules. */
    public record Usage(long last24hMinor, int recentCount) {}

    private final JdbcTemplate jdbc;
    private final JengaProperties.Limits limits;

    public LimitService(JdbcTemplate jdbc, JengaProperties properties) {
        this.jdbc = jdbc;
        this.limits = properties.limits();
    }

    public Usage usage(UUID userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_minor) FILTER (
                           WHERE created_at >= now() - interval '24 hours'
                             AND status IN ('COMPLETED', 'PENDING_APPROVAL')), 0) AS last24h,
                       COUNT(*) FILTER (WHERE created_at >= now() - make_interval(secs => ?)) AS recent
                  FROM transfers
                 WHERE created_by = ? AND created_at >= now() - interval '24 hours'
                """,
                (rs, n) -> new Usage(rs.getLong("last24h"), rs.getInt("recent")),
                limits.velocityWindowSeconds(), userId);
    }

    /** Empty list = within every limit. */
    public List<LimitViolation> check(Usage usage, long amountMinor) {
        List<LimitViolation> violations = new ArrayList<>();
        if (amountMinor > limits.maxPerTransferMinor()) {
            violations.add(new LimitViolation("per_transfer", limits.maxPerTransferMinor(), amountMinor));
        }
        long projected = usage.last24hMinor() + amountMinor;
        if (projected > limits.dailyTotalMinor()) {
            violations.add(new LimitViolation("daily_total", limits.dailyTotalMinor(), projected));
        }
        if (usage.recentCount() >= limits.velocityMaxTransfers()) {
            violations.add(new LimitViolation("velocity", limits.velocityMaxTransfers(), usage.recentCount() + 1L));
        }
        return violations;
    }
}
