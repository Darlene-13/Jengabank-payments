package com.jengabank.payments.limits;

import com.jengabank.payments.config.AppProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Rolling transaction limits.
 *
 * <p>Defence against "limit bypass through decomposition": the check never looks
 * at the current transfer alone, it queries the {@code transfers} table for the
 * owner's total outbound volume over the last hour, 24 hours and 7 days, then
 * adds the attempted amount. Splitting a 1M KES transfer into twenty 50K KES
 * transfers trips the cumulative velocity rule on the transfer that crosses it.
 *
 * <p>PENDING_APPROVAL transfers count towards the windows (queued money is still
 * committed money), BLOCKED and REJECTED ones do not.
 */
@Service
public class LimitService {

    private final JdbcTemplate jdbc;
    private final AppProperties properties;

    public LimitService(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Cumulative outbound volume for {@code ownerId} over the three windows. */
    public WindowTotals totals(String ownerId, Instant now) {
        return jdbc.queryForObject(
                "SELECT "
                        + " COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ?), 0) AS hourly, "
                        + " COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ?), 0) AS daily, "
                        + " COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ?), 0) AS weekly "
                        + "FROM transfers "
                        + "WHERE source_owner_id = ? "
                        + "AND status IN ('COMPLETED', 'PENDING_APPROVAL')",
                (rs, rowNum) -> new WindowTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
                Timestamp.from(now.minus(24, ChronoUnit.HOURS)),
                Timestamp.from(now.minus(7, ChronoUnit.DAYS)),
                ownerId);
    }

    /** Empty violations list means the transfer is within every limit. */
    public List<LimitViolation> check(WindowTotals totals, long amountMinor) {
        List<LimitViolation> violations = new ArrayList<>();

        long perTransfer = properties.getPerTransferLimitMinor();
        if (amountMinor > perTransfer) {
            violations.add(new LimitViolation("per_transfer", perTransfer, amountMinor));
        }

        long hourly = totals.hourlyMinor() + amountMinor;
        if (hourly > properties.getHourlyLimitMinor()) {
            violations.add(new LimitViolation("1h", properties.getHourlyLimitMinor(), hourly));
        }

        long daily = totals.dailyMinor() + amountMinor;
        if (daily > properties.getDailyLimitMinor()) {
            violations.add(new LimitViolation("24h", properties.getDailyLimitMinor(), daily));
        }

        long weekly = totals.weeklyMinor() + amountMinor;
        if (weekly > properties.getWeeklyLimitMinor()) {
            violations.add(new LimitViolation("7d", properties.getWeeklyLimitMinor(), weekly));
        }

        return violations;
    }

    public List<LimitViolation> check(String ownerId, long amountMinor, Instant now) {
        return check(totals(ownerId, now), amountMinor);
    }
}
