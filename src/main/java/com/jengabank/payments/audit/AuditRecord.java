package com.jengabank.payments.audit;

import java.time.Instant;

/**
 * One hash-chained audit row. {@code prevHash}/{@code hash} are filled in by the
 * database trigger - application code never touches them.
 */
public record AuditRecord(
        Long sequence,        // renamed from seq for clarity
        String actor,
        String action,
        String target,
        String details,
        String prevHash,
        String hash,
        Instant ts) {         // renamed from createdAt to match column name
}