package com.jengabank.payments.limits;

/** One broken limit rule for a transfer attempt. */
public record LimitViolation(String window, long limitMinor, long projectedMinor) {

    public String message() {
        return "limit '" + window + "' exceeded: projected " + projectedMinor + " > " + limitMinor;
    }
}
