package com.jengabank.payments.limits;

import com.jengabank.payments.config.AppProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Transaction limits — defence against "limit bypass through decomposition".
 *
 * <p>Windows:
 * <ul>
 *   <li><b>1 minute / 1 hour</b> — rolling (velocity spikes need a rolling check)</li>
 *   <li><b>Daily</b> — resets at Nairobi midnight (21:00 UTC), per team decision G7</li>
 *   <li><b>Weekly</b> — resets at the most recent Nairobi Sunday midnight</li>
 * </ul>
 *
 * <p>Totals are computed under a per-user advisory lock so concurrent transfers
 * from the same owner serialize and cannot slip past a cumulative limit.
 *
 * <p>PENDING_APPROVAL transfers count towards the windows (queued money is still
 * committed money), BLOCKED and REJECTED ones do not.
 */
@Service
public class LimitService {

    /** Team plan: no more than 5 transfers per minute per user. */
    private static final long MINUTE_TRANSFER_LIMIT = 5L;

    /** Team decision G7: daily reset boundary follows Nairobi local time. */
    private static final ZoneId RESET_ZONE = ZoneId.of("Africa/Nairobi");

    private final JdbcTemplate jdbc;
    private final AppProperties properties;

    public LimitService(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Cumulative outbound volume for {@code ownerId} over all rolling windows. */
    public WindowTotals totals(String ownerId, Instant now) {
        // Per-user advisory lock; released automatically at transaction end.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, ownerId);

        ZonedDateTime nowNairobi = now.atZone(RESET_ZONE);

        // Rolling for sub-day windows.
        Instant oneMinuteAgo = now.minus(1, ChronoUnit.MINUTES);
        Instant oneHourAgo   = now.minus(1, ChronoUnit.HOURS);

        // Nairobi-boundary resets for daily and weekly windows.
        Instant startOfTodayNairobi = nowNairobi.toLocalDate()
                .atStartOfDay(RESET_ZONE).toInstant();
        Instant startOfWeekNairobi  = nowNairobi.toLocalDate()
                .minusDays(6).atStartOfDay(RESET_ZONE).toInstant();

        return jdbc.queryForObject(
                "SELECT "
                        + " COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ?), 0) AS hourly, "
                        + " COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ?), 0) AS daily, "
                        + " COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ?), 0) AS weekly, "
                        + " COUNT(*) FILTER (WHERE created_at >= ?) AS minute_count "
                        + "FROM transfers "
                        + "WHERE source_owner_id = ? "
                        + "AND status IN ('COMPLETED', 'PENDING_APPROVAL')",
                (rs, rowNum) -> new WindowTotals(
                        rs.getLong("hourly"),
                        rs.getLong("daily"),
                        rs.getLong("weekly"),
                        rs.getLong("minute_count")),
                Timestamp.from(oneHourAgo),
                Timestamp.from(startOfTodayNairobi),
                Timestamp.from(startOfWeekNairobi),
                Timestamp.from(oneMinuteAgo),
                ownerId);
    }

    /** Empty violations list means the transfer is within every limit. */
    public List<LimitViolation> check(WindowTotals totals, long amountMinor) {
        List<LimitViolation> violations = new ArrayList<>();

        long perTransfer = properties.getPerTransferLimitMinor();
        if (amountMinor > perTransfer) {
            violations.add(new LimitViolation("per_transfer", perTransfer, amountMinor));
        }

        if (totals.minuteCount() >= MINUTE_TRANSFER_LIMIT) {
            violations.add(new LimitViolation("1m", MINUTE_TRANSFER_LIMIT, totals.minuteCount() + 1));
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