package com.jengabank.payments.fraud.rules;

import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudFinding;
import com.jengabank.payments.fraud.FraudRule;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Round-trip flow: the recipient sent money back to the sender recently.
 *
 * <p>Classic layering pattern. The sender pays A, A pays the sender back soon
 * after, and the funds appear "legitimate" by the time they return. Held for
 * review rather than blocked: legitimate refunds and corrections exist, so a
 * second pair of eyes decides.
 */
@Component
public class RoundTripRule implements FraudRule {

    /** Lookback window for the mirrored transfer. */
    private static final long LOOKBACK_MINUTES = 60L;

    private final JdbcTemplate jdbc;

    public RoundTripRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String code() {
        return "fraud.round_trip";
    }

    @Override
    public FraudFinding check(FraudContext context) {
        // New payees can't be round-trips by definition: there's no history yet.
        if (context.newPayee()) {
            return null;
        }

        Instant since = Instant.now().minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES);

        Long mirrors = jdbc.query(
                "SELECT COUNT(*) FROM transfers "
                        + "WHERE source_owner_id = ? AND target_owner_id = ? "
                        + "AND created_at >= ? "
                        + "AND status IN ('COMPLETED', 'PENDING_APPROVAL')",
                rs -> rs.next() ? rs.getLong(1) : null,
                context.targetOwner(), context.sourceOwner(), Timestamp.from(since));

        long count = mirrors == null ? 0L : mirrors;
        if (count > 0) {
            return FraudFinding.review(
                    "recipient sent a transfer in the opposite direction in the last "
                            + LOOKBACK_MINUTES + " minutes");
        }
        return null;
    }
}