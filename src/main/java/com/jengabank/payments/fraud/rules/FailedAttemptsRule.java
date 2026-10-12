package com.jengabank.payments.fraud.rules;

import com.jengabank.payments.config.AppProperties;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudFinding;
import com.jengabank.payments.fraud.FraudRule;
import org.springframework.stereotype.Component;

/**
 * An owner repeatedly punching at blocked transfers is either an attacker or a
 * compromised session: hard-block and let the incident be investigated.
 *
 * <p>Threshold semantics: {@code < 0} disables the rule, {@code 0} blocks every
 * attempt (kill switch), {@code n > 0} blocks from the n-th blocked attempt.
 */
@Component
public class FailedAttemptsRule implements FraudRule {

    private final AppProperties properties;

    public FailedAttemptsRule(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return "fraud.failed_attempts";
    }

    @Override
    public FraudFinding check(FraudContext context) {
        int threshold = properties.getMaxBlockedTransfers24h();
        if (threshold >= 0 && context.blockedLast24h() >= threshold) {
            return FraudFinding.block(
                    context.blockedLast24h() + " blocked transfers in the last 24h (threshold " + threshold + ")");
        }
        return null;
    }
}
