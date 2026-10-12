package com.jengabank.payments.fraud;

/** A single rule's finding: what the rule decided and why. */
public record FraudFinding(FraudDecision decision, String reason) {

    public static FraudFinding review(String reason) {
        return new FraudFinding(FraudDecision.REVIEW, reason);
    }

    public static FraudFinding block(String reason) {
        return new FraudFinding(FraudDecision.BLOCK, reason);
    }

    public static FraudFinding allow(String reason) {
        return new FraudFinding(FraudDecision.ALLOW, reason);
    }
}
