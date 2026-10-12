package com.jengabank.payments.ledger;

public record LedgerEntry(
        Long accountId,
        String accountNo,
        long amountMinor,
        String currency) {
}