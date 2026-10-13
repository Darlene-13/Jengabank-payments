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

public final class TransferDtos {

    private TransferDtos() {}

    /** The only things a customer controls: which of their accounts, to whom, how much, and a note. */
    public record CreateTransferRequest(
            @NotNull UUID fromAccountId,
            @NotBlank @Pattern(regexp = "^[0-9]{6,20}$", message = "must be 6 to 20 digits") String toAccountNumber,
            @NotNull @Positive @Max(100_000_000_000L) Long amountMinor,
            @NotBlank @Pattern(regexp = "^KES$", message = "must be KES") String currency,
            @Size(max = 140) String reference) {}

    public record FraudView(String decision, List<String> reasons) {}

    /** Matches the frontend's Transfer type exactly. */
    public record TransferDto(
            UUID id, UUID fromAccountId, String fromAccountNumber, String toAccountNumber,
            long amountMinor, String currency, String reference, String status, FraudView fraud,
            UUID journalId, String createdBy, OffsetDateTime createdAt,
            String approvedBy, OffsetDateTime approvedAt, String rejectionReason, UUID reversalJournalId) {}
}
