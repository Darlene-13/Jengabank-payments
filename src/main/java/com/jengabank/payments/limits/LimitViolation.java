package com.jengabank.payments.limits;

/** One broken limit rule for a transfer attempt. */
public record LimitViolation(String rule, long limit, long projected) {

    public String message() {
        return switch (rule) {
            case "per_transfer" -> "Amount is above the per-transfer limit of " + limit + " cents.";
            case "daily_total" -> "This would take you past the 24h limit of " + limit + " cents.";
            case "velocity" -> "Too many transfers: the limit is " + limit + " in a short window.";
            default -> "limit '" + rule + "' exceeded";
        };
    }
}
