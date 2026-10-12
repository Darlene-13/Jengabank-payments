package com.jengabank.payments.ledger;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts double-entry journals. Every journal is one DEBIT and one CREDIT of the
 * same amount, so money is only ever moved, never created. The database
 * re-checks balance and overdraft at COMMIT (V3), as the last line of defence.
 *
 * <p>Callers must already hold the account row locks (see {@link LedgerRepository#lock}).
 */
@Service
public class LedgerService {

    private static final int MAX_DESCRIPTION = 140;

    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    /** Money moves from {@code from} to {@code to}. Returns the journal id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postTransfer(UUID from, UUID to, long amountMinor, String description, UUID createdBy) {
        UUID journalId = repository.insertJournal("TRANSFER", clip(description), null, createdBy);
        repository.insertEntry(journalId, from, "DEBIT", amountMinor);
        repository.insertEntry(journalId, to, "CREDIT", amountMinor);
        return journalId;
    }

    /** A new journal with the original entries swapped. The original is never touched. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postReversal(UUID originalJournalId, UUID originalFrom, UUID originalTo, long amountMinor,
                             String description, UUID createdBy) {
        UUID journalId = repository.insertJournal("REVERSAL", clip(description), originalJournalId, createdBy);
        repository.insertEntry(journalId, originalTo, "DEBIT", amountMinor);
        repository.insertEntry(journalId, originalFrom, "CREDIT", amountMinor);
        return journalId;
    }

    private static String clip(String description) {
        return description.length() <= MAX_DESCRIPTION ? description : description.substring(0, MAX_DESCRIPTION);
    }
}
