package com.jengabank.payments.transfer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Shapes from the API contract (jenga-frontend/src/types/api.ts). Money is integer cents. */
public final class TransferDtos {

    private TransferDtos() {}

    public record CreateTransferRequest(
            @NotNull UUID fromAccountId,
            @NotBlank @Pattern(regexp = "^[0-9]{6,20}$") String toAccountNumber,
            @NotNull @Positive @Max(100_000_000_000L) Long amountMinor,
            @NotBlank @Pattern(regexp = "^KES$") String currency,
            @NotNull @Size(max = 140) String reference) {}

    public record FraudDto(String decision, List<String> reasons) {}

    public record TransferDto(
            UUID id,
            UUID fromAccountId,
            String fromAccountNumber,
            String toAccountNumber,
            long amountMinor,
            String currency,
            String reference,
            String status,
            FraudDto fraud,
            UUID journalId,
            String createdBy,
            OffsetDateTime createdAt,
            String approvedBy,
            OffsetDateTime approvedAt,
            String rejectionReason,
            UUID reversalJournalId) {

        public static TransferDto of(Transfer t) {
            return new TransferDto(
                    t.id(), t.fromAccountId(), t.fromAccountNumber(), t.toAccountNumber(),
                    t.amountMinor(), t.currency(), t.reference(), t.status().name(),
                    t.fraudDecision() == null ? null : new FraudDto(t.fraudDecision().name(), t.fraudReasons()),
                    t.journalId(), t.createdByUsername(), t.createdAt(),
                    t.decidedByUsername(), t.decidedAt(), t.rejectionReason(), t.reversalJournalId());
        }
    }
}
