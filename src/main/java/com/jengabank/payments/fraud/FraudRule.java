package com.jengabank.payments.fraud;

/**
 * One rule of the synchronous fraud engine.
 *
 * <p>Implementations are stateless Spring beans; the engine evaluates all of
 * them for every transfer before it is saved and keeps the worst verdict.
 */
public interface FraudRule {

    /** Stable rule identifier, also used in {@code fraud_reasons}. */
    String code();

    /** @return a finding when the rule trips, otherwise {@code null}. */
    FraudFinding check(FraudContext context);
}
