package com.jengabank.payments.ledger;

import java.util.List;

/** The balanced set of entries produced for one transfer. */
public record Journal(String reference, List<LedgerEntry> entries) {

    public long net() {
        return entries.stream().mapToLong(LedgerEntry::amountMinor).sum();
    }
}
