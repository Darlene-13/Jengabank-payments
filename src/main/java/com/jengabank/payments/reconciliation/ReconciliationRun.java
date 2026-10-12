package com.jengabank.payments.reconciliation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReconciliationRun(UUID id, OffsetDateTime runAt, String status, List<ReconciliationCheck> checks) {
}
