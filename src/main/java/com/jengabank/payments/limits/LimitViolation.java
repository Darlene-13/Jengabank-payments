package com.jengabank.payments.limits;

/** One broken limit rule for a transfer attempt. */
public record LimitViolation(String window, long limitMinor, long projectedMinor) {

    /**
     * Message includes the window code ("24h", "1h", "per_transfer", "7d", "1m")
     * so tests and clients can identify which limit was hit.
     */
    public String message() {
        return "limit '" + window + "' exceeded: projected " + projectedMinor + " > " + limitMinor;
    }
}