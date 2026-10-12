package com.jengabank.payments.fraud.rules;

import com.jengabank.payments.config.AppProperties;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudFinding;
import com.jengabank.payments.fraud.FraudRule;
import org.springframework.stereotype.Component;

/**
 * A large first payment to a payee we have never paid before is the highest-risk
 * pattern in retail payments: hold it for a checker.
 */
@Component
public class LargeAmountNewPayeeRule implements FraudRule {

    private final AppProperties properties;

    public LargeAmountNewPayeeRule(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return "fraud.large_amount_new_payee";
    }

    @Override
    public FraudFinding check(FraudContext context) {
        long threshold = properties.getLargeAmountNewPayeeMinor();
        if (context.newPayee() && context.amountMinor() >= threshold) {
            return FraudFinding.review(
                    "first payment of " + context.amountMinor() + " to new payee "
                            + context.targetOwner() + " (threshold " + threshold + ")");
        }
        return null;
    }
}
