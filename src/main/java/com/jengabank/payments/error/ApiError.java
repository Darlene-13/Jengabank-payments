package com.jengabank.payments.error;

import java.time.Instant;

/** Single machine-readable error body for every non-2xx response. */
public record ApiError(
        Instant ts,
        int status,
        String error,
        String code,
        String message,
        String path) {
}
