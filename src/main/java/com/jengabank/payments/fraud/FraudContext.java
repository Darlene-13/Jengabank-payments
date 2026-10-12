package com.jengabank.payments.fraud;

/**
 * Everything a rule needs to know about one transfer attempt, computed
 * synchronously by the transfer flow before anything is persisted.
 *
 * <p>Volumes are cumulative outbound totals for the source owner and exclude the
 * attempted amount, which the caller adds when evaluating thresholds.
 */
public record FraudContext(
        String sourceOwner,
        String targetOwner,
        String sourceAccountNo,
        String targetAccountNo,
        long amountMinor,
        String currency,
        int hourOfDay,
        long amountLastHourMinor,
        long amountLast24hMinor,
        boolean newPayee,
        int blockedLast24h,
        long perTransferLimitMinor,
        long hourlyLimitMinor,
        long dailyLimitMinor,
        double structuringRatio) {
}
