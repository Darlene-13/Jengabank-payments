package com.jengabank.payments.ledger;

import java.util.UUID;

/** The parts of an account the money paths need. ownerId is null for the bank's own accounts. */
public record LedgerAccount(UUID id, String accountNumber, UUID ownerId, String type, String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isCustomer() {
        return "CUSTOMER".equals(type);
    }
}
