package com.jengabank.payments.limits;

/** Cumulative outbound volume of one owner across the rolling windows. */
public record WindowTotals(
        long hourlyMinor,
        long dailyMinor,
        long weeklyMinor,
        long minuteCount) {

    public static WindowTotals empty() {
        return new WindowTotals(0L, 0L, 0L, 0L);
    }
}