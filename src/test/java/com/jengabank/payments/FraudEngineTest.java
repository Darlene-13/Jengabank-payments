package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.jengabank.payments.config.AppProperties;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudDecision;
import com.jengabank.payments.fraud.FraudEngine;
import com.jengabank.payments.fraud.FraudVerdict;
import com.jengabank.payments.fraud.rules.FailedAttemptsRule;
import com.jengabank.payments.fraud.rules.LargeAmountNewPayeeRule;
import com.jengabank.payments.fraud.rules.OddHoursRule;
import com.jengabank.payments.fraud.rules.StructuringRule;
import com.jengabank.payments.fraud.rules.VelocityRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the synchronous fraud rules: structuring, odd hours,
 * new-payee risk, repeated blocked attempts and the worst-verdict-wins policy.
 */
class FraudEngineTest {

    private static final long PER_TRANSFER_LIMIT = 500_000L;
    private static final long HOURLY_LIMIT = 1_000_000L;
    private static final long DAILY_LIMIT = 5_000_000L;

    private AppProperties properties() {
        AppProperties props = new AppProperties();
        props.setPerTransferLimitMinor(PER_TRANSFER_LIMIT);
        props.setHourlyLimitMinor(HOURLY_LIMIT);
        props.setDailyLimitMinor(DAILY_LIMIT);
        props.setStructuringRatio(0.95);
        props.setOddHourStart(23);
        props.setOddHourEnd(5);
        props.setLargeAmountNewPayeeMinor(100_000L);
        props.setMaxBlockedTransfers24h(3);
        return props;
    }

    private FraudEngine engine(AppProperties props) {
        return new FraudEngine(List.of(
                new StructuringRule(),
                new VelocityRule(),
                new OddHoursRule(props),
                new LargeAmountNewPayeeRule(props),
                new FailedAttemptsRule(props)));
    }

    private FraudContext context(long amount, int hour, long lastHour, long last24h,
                                 boolean newPayee, int blocked24h) {
        return new FraudContext(
                "alice", "bob", "1001", "1002", amount, "KES", hour,
                lastHour, last24h, newPayee, blocked24h,
                PER_TRANSFER_LIMIT, HOURLY_LIMIT, DAILY_LIMIT, 0.95);
    }

    @Test
    void cleanTransferIsAllowed() {
        FraudVerdict verdict = engine(properties())
                .evaluate(context(1_000L, 12, 0L, 0L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.ALLOW);
        assertThat(verdict.reasons()).isEmpty();
    }

    @Test
    void amountJustBelowTheLimitIsTreatedAsStructuring() {
        // 499,999 against a 500,000 limit: the classic threshold-slippage signature.
        FraudVerdict verdict = engine(properties())
                .evaluate(context(499_999L, 12, 0L, 0L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.structuring"));
    }

    @Test
    void oddHoursAreFlaggedForReview() {
        AppProperties props = properties();
        props.setOddHourStart(23);
        props.setOddHourEnd(5);
        FraudVerdict verdict = engine(props).evaluate(context(1_000L, 3, 0L, 0L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.odd_hours"));
    }

    @Test
    void oddHourRuleCanBeDisabledBySettingStartEqualsEnd() {
        AppProperties props = properties();
        props.setOddHourStart(0);
        props.setOddHourEnd(0);
        FraudVerdict verdict = engine(props).evaluate(context(1_000L, 3, 0L, 0L, false, 0));
        assertThat(verdict.reasons()).noneMatch(r -> r.startsWith("fraud.odd_hours"));
    }

    @Test
    void largeFirstPaymentToNewPayeeIsFlagged() {
        FraudVerdict verdict = engine(properties())
                .evaluate(context(150_000L, 12, 0L, 0L, true, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.large_amount_new_payee"));
    }

    @Test
    void repeatedBlockedAttemptsAreHardBlocked() {
        FraudVerdict verdict = engine(properties())
                .evaluate(context(1_000L, 12, 0L, 0L, false, 3));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.BLOCK);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.failed_attempts"));
    }

    @Test
    void cumulativeVolumeNearTheDailyAppetiteIsFlagged() {
        FraudVerdict verdict = engine(properties())
                .evaluate(context(900_000L, 12, 0L, 4_000_000L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.velocity"));
    }

    @Test
    void blockOutranksReview() {
        AppProperties props = properties();
        props.setOddHourStart(23);
        props.setOddHourEnd(5);
        // Odd hours => REVIEW, repeated blocked attempts => BLOCK. BLOCK must win.
        FraudVerdict verdict = engine(props).evaluate(context(499_999L, 2, 0L, 0L, true, 5));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.BLOCK);
        assertThat(verdict.reasons()).hasSizeGreaterThanOrEqualTo(3);
    }
}
