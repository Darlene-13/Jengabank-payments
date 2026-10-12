package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jengabank.payments.config.JengaProperties;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudDecision;
import com.jengabank.payments.fraud.FraudEngine;
import com.jengabank.payments.fraud.FraudVerdict;
import com.jengabank.payments.fraud.rules.FailedAttemptsRule;
import com.jengabank.payments.fraud.rules.LargeAmountNewPayeeRule;
import com.jengabank.payments.fraud.rules.OddHoursRule;
import com.jengabank.payments.fraud.rules.StructuringRule;
import com.jengabank.payments.fraud.rules.VelocityRule;

/**
 * Pure unit tests for the synchronous fraud rules: structuring, odd hours,
 * new-payee risk, repeated blocked attempts and the worst-verdict-wins policy.
 */
class FraudEngineTest {

    private static final long PER_TRANSFER_LIMIT = 500_000L;
    private static final long DAILY_LIMIT = 5_000_000L;

    private JengaProperties properties(int oddStart, int oddEnd) {
        return new JengaProperties(
                new JengaProperties.Limits(PER_TRANSFER_LIMIT, DAILY_LIMIT, 5, 60),
                new JengaProperties.Approval(250_000L),
                new JengaProperties.Fraud("Africa/Nairobi", oddStart, oddEnd, 100_000L, 0.95, 3));
    }

    private FraudEngine engine(JengaProperties props) {
        return new FraudEngine(List.of(
                new StructuringRule(props),
                new VelocityRule(),
                new OddHoursRule(props),
                new LargeAmountNewPayeeRule(props),
                new FailedAttemptsRule(props)));
    }

    private FraudContext context(long amount, int hour, long last24h, boolean newPayee, int blocked24h) {
        return new FraudContext(amount, hour, last24h, newPayee, blocked24h, PER_TRANSFER_LIMIT, DAILY_LIMIT);
    }

    @Test
    void cleanTransferIsAllowed() {
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(1_000L, 12, 0L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.ALLOW);
        assertThat(verdict.reasons()).isEmpty();
    }

    @Test
    void amountJustBelowTheLimitIsTreatedAsStructuring() {
        // 499,999 against a 500,000 limit: the classic threshold-slippage signature.
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(499_999L, 12, 0L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.structuring"));
    }

    @Test
    void oddHoursAreFlaggedForReview() {
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(1_000L, 3, 0L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.odd_hours"));
    }

    @Test
    void oddHourRuleCanBeDisabledBySettingStartEqualsEnd() {
        FraudVerdict verdict = engine(properties(0, 0)).evaluate(context(1_000L, 3, 0L, false, 0));
        assertThat(verdict.reasons()).noneMatch(r -> r.startsWith("fraud.odd_hours"));
    }

    @Test
    void largeFirstPaymentToNewPayeeIsFlagged() {
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(150_000L, 12, 0L, true, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.large_amount_new_payee"));
    }

    @Test
    void repeatedBlockedAttemptsAreHardBlocked() {
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(1_000L, 12, 0L, false, 3));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.BLOCK);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.failed_attempts"));
    }

    @Test
    void cumulativeVolumeNearTheDailyAppetiteIsFlagged() {
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(400_000L, 12, 4_200_000L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(verdict.reasons()).anyMatch(r -> r.startsWith("fraud.velocity"));
    }

    @Test
    void reachingTheDailyLimitIsBlocked() {
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(400_000L, 12, 4_600_000L, false, 0));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.BLOCK);
    }

    @Test
    void blockOutranksReview() {
        // Odd hours and structuring => REVIEW, repeated blocked attempts => BLOCK. BLOCK must win.
        FraudVerdict verdict = engine(properties(23, 5)).evaluate(context(499_999L, 2, 0L, true, 5));
        assertThat(verdict.decision()).isEqualTo(FraudDecision.BLOCK);
        assertThat(verdict.reasons()).hasSizeGreaterThanOrEqualTo(3);
    }
}
