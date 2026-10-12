package com.jengabank.payments.reversal;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ReversalDtos {

    private ReversalDtos() {}

    /** The reason goes on the audit trail, so it must say something (10+ characters, as in V1). */
    public record ReversalRequest(@NotBlank @Size(min = 10, max = 280) String reason) {}

    public record ReversalDto(UUID id, UUID transferId, String fromAccountNumber, String toAccountNumber,
                              long amountMinor, String reason, String status, String requestedBy,
                              OffsetDateTime requestedAt) {}
}
