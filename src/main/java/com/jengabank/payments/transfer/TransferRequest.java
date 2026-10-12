package com.jengabank.payments.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Body of {@code POST /transfers}. Amounts are minor units (100 = 1.00 KES). */
public record TransferRequest(
        @NotBlank String sourceAccountNo,
        @NotBlank String targetAccountNo,
        @Positive long amountMinor,
        String currency) {
}
