package com.jengabank.payments.transfer;

/** Lifecycle of a transfer. Only COMPLETED (and later REVERSED) has a journal. */
public enum TransferStatus {

    /** Held for a checker: over the approval threshold or flagged by fraud. No journal yet. */
    PENDING_APPROVAL,

    /** Journaled: money has moved. */
    COMPLETED,

    /** Fraud engine refused it; kept as evidence, no journal. */
    BLOCKED,

    /** A checker refused it; no journal. */
    REJECTED,

    /** Was COMPLETED; a reversal journal has moved the money back. */
    REVERSED
}
