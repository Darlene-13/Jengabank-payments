package com.jengabank.payments.reversal;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ReversalDtos {

    private ReversalDtos() {}

    public record ReversalRequest(@NotBlank @Size(min = 10, max = 280) String reason) {}

    public record RejectRequest(@NotBlank @Size(min = 5, max = 280) String reason) {}

    public record ReversalDto(UUID id, UUID transferId, String status, String reason, long amountMinor,
                              String fromAccountNumber, String toAccountNumber, UUID journalId,
                              String requestedBy, OffsetDateTime requestedAt,
                              String decidedBy, OffsetDateTime decidedAt) {}
}
