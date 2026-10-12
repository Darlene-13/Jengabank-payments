package com.jengabank.payments.transfer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.jengabank.payments.fraud.FraudDecision;

/** One row of {@code transfers}, joined with account numbers and usernames. */
public record Transfer(
        UUID id,
        UUID fromAccountId,
        String fromAccountNumber,
        UUID fromOwnerId,
        UUID toAccountId,
        String toAccountNumber,
        UUID toOwnerId,
        long amountMinor,
        String currency,
        String reference,
        TransferStatus status,
        FraudDecision fraudDecision,
        List<String> fraudReasons,
        UUID journalId,
        UUID reversalJournalId,
        UUID createdBy,
        String createdByUsername,
        OffsetDateTime createdAt,
        UUID decidedBy,
        String decidedByUsername,
        OffsetDateTime decidedAt,
        String rejectionReason) {

    /** Customers see a transfer only if one side is their account. Staff see all. */
    public boolean visibleTo(UUID userId) {
        return userId.equals(fromOwnerId) || userId.equals(toOwnerId);
    }
}
