package com.jengabank.payments.fraud;

/**
 * Everything a rule needs to know about one transfer attempt, computed
 * synchronously by the transfer flow before anything is persisted.
 *
 * <p>{@code amountLast24hMinor} is the customer's outbound total and excludes the
 * attempted amount, which rules add themselves.
 */
public record FraudContext(
        long amountMinor,
        int localHour,
        long amountLast24hMinor,
        boolean newPayee,
        int blockedLast24h,
        long perTransferLimitMinor,
        long dailyLimitMinor) {
}
