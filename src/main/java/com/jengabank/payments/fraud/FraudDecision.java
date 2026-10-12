package com.jengabank.payments.fraud;

/** Verdict of the fraud engine, ordered ALLOW &lt; REVIEW &lt; BLOCK. */
public enum FraudDecision {

    ALLOW(0),
    REVIEW(1),
    BLOCK(2);

    private final int severity;

    FraudDecision(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }

    public boolean worseThan(FraudDecision other) {
        return this.severity > other.severity;
    }
}
