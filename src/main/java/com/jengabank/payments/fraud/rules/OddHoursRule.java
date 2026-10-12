package com.jengabank.payments.fraud.rules;

import com.jengabank.payments.config.JengaProperties;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudFinding;
import com.jengabank.payments.fraud.FraudRule;
import org.springframework.stereotype.Component;

/**
 * Late-night transfers are a classic account-takeover pattern: hold for review.
 *
 * <p>The window is inclusive of {@code odd-hour-start} and exclusive of
 * {@code odd-hour-end}; configuring start == end disables the rule.
 */
@Component
public class OddHoursRule implements FraudRule {

    private final JengaProperties properties;

    public OddHoursRule(JengaProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return "fraud.odd_hours";
    }

    @Override
    public FraudFinding check(FraudContext context) {
        int start = normalize(properties.fraud().oddHourStart());
        int end = normalize(properties.fraud().oddHourEnd());
        if (start == end) {
            return null;
        }
        int hour = context.localHour();
        boolean inWindow = start < end ? (hour >= start && hour < end) : (hour >= start || hour < end);
        if (inWindow) {
            return FraudFinding.review("transfer attempted at hour " + hour + " local time");
        }
        return null;
    }

    private static int normalize(int hour) {
        int h = hour % 24;
        return h < 0 ? h + 24 : h;
    }
}
