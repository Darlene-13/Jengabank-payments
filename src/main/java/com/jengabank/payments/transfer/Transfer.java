package com.jengabank.payments.transfer;

import com.jengabank.payments.fraud.FraudDecision;
import java.time.Instant;
import java.util.List;

public record Transfer(
        Long id,
        String reference,
        String sourceAccountNo,
        String targetAccountNo,
        String sourceOwnerId,
        String targetOwnerId,
        long amountMinor,
        String currency,
        TransferStatus status,
        FraudDecision fraudDecision,
        List<String> fraudReasons,
        String createdBy,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt) {
}