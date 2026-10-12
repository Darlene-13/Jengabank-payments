package com.jengabank.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Flat application properties (see {@code app.*} in application.yml).
 * Limit values are in minor units (100 minor units = 1.00 KES).
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String currency = "KES";
    private String settlementAccountNo = "9000";

    private long perTransferLimitMinor = 50_000_000L;
    private long hourlyLimitMinor = 100_000_000L;
    private long dailyLimitMinor = 200_000_000L;
    private long weeklyLimitMinor = 1_000_000_000L;
    private double structuringRatio = 0.95;

    private int oddHourStart = 23;
    private int oddHourEnd = 5;
    private long largeAmountNewPayeeMinor = 10_000_000L;
    private int maxBlockedTransfers24h = 3;

    private String reconciliationCron = "0 0 2 * * *";

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getSettlementAccountNo() {
        return settlementAccountNo;
    }

    public void setSettlementAccountNo(String settlementAccountNo) {
        this.settlementAccountNo = settlementAccountNo;
    }

    public long getPerTransferLimitMinor() {
        return perTransferLimitMinor;
    }

    public void setPerTransferLimitMinor(long perTransferLimitMinor) {
        this.perTransferLimitMinor = perTransferLimitMinor;
    }

    public long getHourlyLimitMinor() {
        return hourlyLimitMinor;
    }

    public void setHourlyLimitMinor(long hourlyLimitMinor) {
        this.hourlyLimitMinor = hourlyLimitMinor;
    }

    public long getDailyLimitMinor() {
        return dailyLimitMinor;
    }

    public void setDailyLimitMinor(long dailyLimitMinor) {
        this.dailyLimitMinor = dailyLimitMinor;
    }

    public long getWeeklyLimitMinor() {
        return weeklyLimitMinor;
    }

    public void setWeeklyLimitMinor(long weeklyLimitMinor) {
        this.weeklyLimitMinor = weeklyLimitMinor;
    }

    public double getStructuringRatio() {
        return structuringRatio;
    }

    public void setStructuringRatio(double structuringRatio) {
        this.structuringRatio = structuringRatio;
    }

    public int getOddHourStart() {
        return oddHourStart;
    }

    public void setOddHourStart(int oddHourStart) {
        this.oddHourStart = oddHourStart;
    }

    public int getOddHourEnd() {
        return oddHourEnd;
    }

    public void setOddHourEnd(int oddHourEnd) {
        this.oddHourEnd = oddHourEnd;
    }

    public long getLargeAmountNewPayeeMinor() {
        return largeAmountNewPayeeMinor;
    }

    public void setLargeAmountNewPayeeMinor(long largeAmountNewPayeeMinor) {
        this.largeAmountNewPayeeMinor = largeAmountNewPayeeMinor;
    }

    public int getMaxBlockedTransfers24h() {
        return maxBlockedTransfers24h;
    }

    public void setMaxBlockedTransfers24h(int maxBlockedTransfers24h) {
        this.maxBlockedTransfers24h = maxBlockedTransfers24h;
    }

    public String getReconciliationCron() {
        return reconciliationCron;
    }

    public void setReconciliationCron(String reconciliationCron) {
        this.reconciliationCron = reconciliationCron;
    }
}
