package com.jengabank.payments.fraud.rules;

import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudFinding;
import com.jengabank.payments.fraud.FraudRule;
import org.springframework.stereotype.Component;

/**
 * Cumulative velocity guard: money moved in the last hour or the last 24 hours
 * is counted, so splitting one large transfer into many small ones cannot slip
 * past the daily appetite.
 */
@Component
public class VelocityRule implements FraudRule {

    @Override
    public String code() {
        return "fraud.velocity";
    }

    @Override
    public FraudFinding check(FraudContext context) {
        long hourlyLimit = context.hourlyLimitMinor();
        long dailyLimit = context.dailyLimitMinor();
        long projectedHour = context.amountLastHourMinor() + context.amountMinor();
        long projectedDay = context.amountLast24hMinor() + context.amountMinor();

        if (hourlyLimit > 0 && projectedHour > hourlyLimit) {
            return FraudFinding.block(
                    "projected 1h volume " + projectedHour + " exceeds hourly limit " + hourlyLimit);
        }
        if (dailyLimit > 0 && projectedDay >= dailyLimit) {
            return FraudFinding.block(
                    "projected 24h volume " + projectedDay + " reaches daily limit " + dailyLimit);
        }
        if (dailyLimit > 0 && projectedDay * 10 >= dailyLimit * 9) {
            return FraudFinding.review(
                    "projected 24h volume " + projectedDay + " within 10% of daily limit " + dailyLimit);
        }
        return null;
    }
}
