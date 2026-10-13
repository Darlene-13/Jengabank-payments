package com.jengabank.payments.transfer;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Safe defaults used until Dev 3's implementations exist. Not Spring beans on purpose. */
final class BaselineControls {

    private BaselineControls() {}

    static String kes(long minor) {
        return String.format("KES %,d", minor / 100);
    }

    /** Per-transfer max, daily total on the Nairobi calendar day (G7), velocity per window. */
    static final class Limits implements LimitChecker {
        private final JdbcTemplate jdbc;
        private final long maxPerTransfer;
        private final long dailyTotal;
        private final int velocityMax;
        private final int velocityWindowSeconds;

        Limits(JdbcTemplate jdbc, long maxPerTransfer, long dailyTotal, int velocityMax, int velocityWindowSeconds) {
            this.jdbc = jdbc;
            this.maxPerTransfer = maxPerTransfer;
            this.dailyTotal = dailyTotal;
            this.velocityMax = velocityMax;
            this.velocityWindowSeconds = velocityWindowSeconds;
        }

        @Override
        public List<String> violations(UUID customerId, UUID fromAccountId, long amountMinor) {
            List<String> out = new ArrayList<>();
            if (amountMinor > maxPerTransfer) {
                out.add("Above the single-transfer limit of " + kes(maxPerTransfer));
            }
            Long today = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount_minor), 0) FROM transfers
                     WHERE created_by = ? AND status IN ('COMPLETED', 'PENDING_APPROVAL')
                       AND created_at >= (date_trunc('day', now() AT TIME ZONE 'Africa/Nairobi') AT TIME ZONE 'Africa/Nairobi')
                    """, Long.class, customerId);
            if (today != null && today + amountMinor > dailyTotal) {
                out.add("Above the daily limit of " + kes(dailyTotal));
            }
            Integer recent = jdbc.queryForObject(
                    "SELECT count(*) FROM transfers WHERE created_by = ? AND created_at > now() - make_interval(secs => ?)",
                    Integer.class, customerId, velocityWindowSeconds);
            if (recent != null && recent >= velocityMax) {
                out.add("Too many transfers in a short time. Wait a minute and try again");
            }
            return out;
        }
    }

    /** Simple, explainable rules. Any BLOCK wins; otherwise any REVIEW; otherwise ALLOW. */
    static final class Fraud implements FraudEngine {
        private static final long NEW_PAYEE_REVIEW_MINOR = 10_000_000L;  // KES 100,000
        private static final long NIGHT_REVIEW_MINOR = 5_000_000L;       // KES 50,000
        private static final int BLOCKED_ATTEMPTS_LIMIT = 3;
        private static final int ROUND_TRIP_LOOKBACK_MINUTES = 60;
        private final JdbcTemplate jdbc;

        Fraud(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Result evaluate(Context c) {
            List<String> block = new ArrayList<>();
            List<String> review = new ArrayList<>();

            Integer blockedRecently = jdbc.queryForObject("""
                    SELECT count(*) FROM transfers
                     WHERE created_by = ? AND status = 'BLOCKED' AND created_at > now() - interval '10 minutes'""",
                    Integer.class, c.customerId());
            if (blockedRecently != null && blockedRecently >= BLOCKED_ATTEMPTS_LIMIT) {
                block.add("Several blocked attempts in the last 10 minutes");
            }

            Boolean paidBefore = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM transfers
                                    WHERE created_by = ? AND to_account_id = ? AND status IN ('COMPLETED', 'REVERSED'))""",
                    Boolean.class, c.customerId(), c.toAccountId());
            if (!Boolean.TRUE.equals(paidBefore) && c.amountMinor() >= NEW_PAYEE_REVIEW_MINOR) {
                review.add("Large amount to an account you haven't paid before");
            }

            // Round trip (layering): the recipient's account paid this customer within the last hour.
            // Held for review, not blocked: refunds and corrections are legitimate, so a checker decides.
            Boolean roundTrip = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM transfers t JOIN accounts a ON a.id = t.to_account_id
                                    WHERE t.from_account_id = ? AND a.owner_id = ?
                                      AND t.status IN ('COMPLETED', 'PENDING_APPROVAL')
                                      AND t.created_at > now() - make_interval(mins => ?))""",
                    Boolean.class, c.toAccountId(), c.customerId(), ROUND_TRIP_LOOKBACK_MINUTES);
            if (Boolean.TRUE.equals(roundTrip)) {
                review.add("This account sent you money in the last hour; sending it straight back needs a second check");
            }

            int hour = ZonedDateTime.now(ZoneId.of("Africa/Nairobi")).getHour();
            if (hour < 5 && c.amountMinor() >= NIGHT_REVIEW_MINOR) {
                review.add("Large transfer at an unusual hour");
            }

            if (!block.isEmpty()) {
                return new Result(Decision.BLOCK, block);
            }
            if (!review.isEmpty()) {
                return new Result(Decision.REVIEW, review);
            }
            return new Result(Decision.ALLOW, List.of());
        }
    }
}
