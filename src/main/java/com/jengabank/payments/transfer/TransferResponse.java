package com.jengabank.payments.transfer;

import java.time.Instant;
import java.util.List;

public record TransferResponse(
        Long id,
        String reference,
        String status,
        String fraudDecision,
        List<String> fraudReasons,
        long amountMinor,
        String currency,
        String sourceAccountNo,
        String targetAccountNo,
        String createdBy,
        String decidedBy,
        Instant createdAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.id(),
                transfer.reference(),
                transfer.status().name(),
                transfer.fraudDecision().name(),
                transfer.fraudReasons(),
                transfer.amountMinor(),
                transfer.currency(),
                transfer.sourceAccountNo(),
                transfer.targetAccountNo(),
                transfer.createdBy(),
                transfer.decidedBy(),
                transfer.createdAt());
    }

    public boolean isBlocked() {
        return TransferStatus.BLOCKED.name().equals(status);
    }
}