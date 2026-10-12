package com.jengabank.payments.idempotency;

import java.time.Instant;

/** A cached response for a previously seen {@code Idempotency-Key}. */
public record IdempotencyRecord(
        String key,
        String requestHash,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}
