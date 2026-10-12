package com.jengabank.payments.transfer;

/** Lifecycle of a transfer. Only {@link #COMPLETED} moves money. */
public enum TransferStatus {

    /** Fraud asked for a second pair of eyes; no journal posted yet. */
    PENDING_APPROVAL,

    /** Approved (or fraud-allowed) and journaled. */
    COMPLETED,

    /** Fraud engine rejected it; stored as evidence, no journal. */
    BLOCKED,

    /** A checker rejected it; no journal. */
    REJECTED;

    public boolean isPosted() {
        return this == COMPLETED;
    }
}
