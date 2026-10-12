package com.jengabank.payments.approval;

import java.time.Instant;

public record ApprovalResponse(
        Long transferId,
        String reference,
        String status,
        String decision,
        String decidedBy,
        long amountMinor,
        String currency,
        Instant decidedAt,
        String message) {
}