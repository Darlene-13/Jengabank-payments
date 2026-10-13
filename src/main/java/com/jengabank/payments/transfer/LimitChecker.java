package com.jengabank.payments.transfer;

import java.util.List;
import java.util.UUID;

/**
 * Dev 3: implement this as a @Service in limits/ and it replaces the baseline automatically.
 * Called inside the transfer transaction, after the customer's row is locked, so counts are race-free.
 * Return human-readable violations; an empty list means the transfer is within limits.
 */
public interface LimitChecker {
    List<String> violations(UUID customerId, UUID fromAccountId, long amountMinor);
}
