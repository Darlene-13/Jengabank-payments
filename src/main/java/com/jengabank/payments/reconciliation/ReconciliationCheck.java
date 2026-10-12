package com.jengabank.payments.reconciliation;

/**
 * One proof that reconciliation runs. {@code ok} is true when the check passed.
 * Serialised into the {@code reconciliation_runs.checks} JSONB column.
 */
public record ReconciliationCheck(String name, boolean ok, String detail) {
}