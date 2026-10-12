package com.jengabank.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Business rules from {@code jengabank.*} in application.yml. Amounts are in cents. */
@ConfigurationProperties(prefix = "jengabank")
public record JengaProperties(Limits limits, Approval approval, Fraud fraud) {

    public record Limits(long maxPerTransferMinor, long dailyTotalMinor,
                         int velocityMaxTransfers, int velocityWindowSeconds) {}

    public record Approval(long thresholdMinor) {}

    public record Fraud(String zone, int oddHourStart, int oddHourEnd, long largeAmountNewPayeeMinor,
                        double structuringRatio, int maxBlockedTransfers24h) {}
}
