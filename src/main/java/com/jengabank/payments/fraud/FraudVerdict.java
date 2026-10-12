package com.jengabank.payments.fraud;

import java.util.List;

/** Combined outcome of every rule for one transfer. */
public record FraudVerdict(FraudDecision decision, List<String> reasons) {

    public static FraudVerdict allow() {
        return new FraudVerdict(FraudDecision.ALLOW, List.of());
    }
}
