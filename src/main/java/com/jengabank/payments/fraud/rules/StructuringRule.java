package com.jengabank.payments.fraud.rules;

import com.jengabank.payments.config.JengaProperties;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudFinding;
import com.jengabank.payments.fraud.FraudRule;
import org.springframework.stereotype.Component;

/**
 * Threshold slippage / structuring detection: an amount that sits just below the
 * per-transfer limit is the classic signature of limit evasion.
 */
@Component
public class StructuringRule implements FraudRule {

    private final JengaProperties properties;

    public StructuringRule(JengaProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return "fraud.structuring";
    }

    @Override
    public FraudFinding check(FraudContext context) {
        long limit = context.perTransferLimitMinor();
        if (limit <= 0) {
            return null;
        }
        double ratio = properties.fraud().structuringRatio() <= 0 ? 1.0 : properties.fraud().structuringRatio();
        long threshold = (long) (limit * ratio);
        if (context.amountMinor() >= threshold && context.amountMinor() < limit) {
            return FraudFinding.review(
                    "amount " + context.amountMinor() + " within " + Math.round(ratio * 100)
                            + "% of per-transfer limit " + limit);
        }
        return null;
    }
}
