package com.jengabank.payments.reconciliation;

/** One independent proof. Stored in the {@code reconciliation_runs.checks} JSONB column. */
public record ReconciliationCheck(String name, boolean passed, String detail) {
}
