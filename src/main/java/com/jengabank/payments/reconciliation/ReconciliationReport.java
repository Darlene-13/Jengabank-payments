package com.jengabank.payments.reconciliation;

import java.time.Instant;
import java.util.List;

/**
 * Result of one end-of-day reconciliation run.
 *
 * <p>Canonical constructor carries every field the API exposes. A shorter
 * convenience constructor is provided for {@code ReconciliationRepository},
 * which after an INSERT only knows id + status + checks + runAt + durationMs.
 */
public record ReconciliationReport(
        Long id,
        Instant runAt,
        String status,
        boolean passed,
        List<ReconciliationCheck> checks,
        List<String> failedChecks,
        String triggeredBy,
        long durationMs) {

    /**
     * Called by {@code ReconciliationRepository} after inserting a run.
     * {@code passed} and {@code failedChecks} are derived from status + checks;
     * {@code triggeredBy} is filled in by the caller that already knows it.
     */
    public ReconciliationReport(long id, String status,
                                List<ReconciliationCheck> checks,
                                Instant runAt, long durationMs) {
        this(
                id,
                runAt,
                status,
                "PASSED".equals(status),
                checks,
                checks == null ? List.of()
                        : checks.stream()
                        .filter(c -> !c.ok())
                        .map(ReconciliationCheck::name)
                        .toList(),
                null,
                durationMs);
    }
}