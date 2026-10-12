package com.jengabank.payments.ledger;

import com.jengabank.payments.account.Account;
import com.jengabank.payments.account.AccountRepository;
import com.jengabank.payments.config.AppProperties;
import com.jengabank.payments.transfer.Transfer;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts double-entry journals.
 *
 * <p>Every journal is a balanced pair: the source account is debited and the
 * target credited the same amount, so the ledger can never create or destroy
 * value. The database re-checks this invariant at commit time (V3 trigger).
 */
@Service
public class LedgerService {

    public static final String ENTRY_TRANSFER = "TRANSFER";
    public static final String ENTRY_OPENING = "OPENING";

    private final LedgerRepository repository;
    private final AccountRepository accounts;
    private final AppProperties properties;

    public LedgerService(LedgerRepository repository, AccountRepository accounts, AppProperties properties) {
        this.repository = repository;
        this.accounts = accounts;
        this.properties = properties;
    }

    /** Money moves: source -amount, target +amount, both for the same transfer. */
    @Transactional
    public Journal post(Transfer transfer) {
        Account source = requireAccount(transfer.sourceAccountNo());
        Account target = requireAccount(transfer.targetAccountNo());

        Journal journal = new Journal(transfer.reference(), List.of(
                new LedgerEntry(source.id(), source.accountNo(), -transfer.amountMinor(), transfer.currency()),
                new LedgerEntry(target.id(), target.accountNo(), transfer.amountMinor(), transfer.currency())));

        if (journal.net() != 0) {
            throw new IllegalStateException("unbalanced journal for " + transfer.reference());
        }
        for (LedgerEntry entry : journal.entries()) {
            repository.insert(transfer.id(), ENTRY_TRANSFER, entry.accountId(),
                    entry.amountMinor(), entry.currency(), transfer.reference());
        }
        return journal;
    }

    /** Opening balance: funded from the settlement (bank equity) account. */
    @Transactional
    public Journal postOpening(Account account, long amountMinor) {
        Account settlement = requireAccount(properties.getSettlementAccountNo());
        Journal journal = new Journal("OPENING:" + account.accountNo(), List.of(
                new LedgerEntry(settlement.id(), settlement.accountNo(), -amountMinor, properties.getCurrency()),
                new LedgerEntry(account.id(), account.accountNo(), amountMinor, properties.getCurrency())));
        if (journal.net() != 0) {
            throw new IllegalStateException("unbalanced opening journal for " + account.accountNo());
        }
        for (LedgerEntry entry : journal.entries()) {
            repository.insert(null, ENTRY_OPENING, entry.accountId(),
                    entry.amountMinor(), entry.currency(), "opening balance " + account.accountNo());
        }
        return journal;
    }

    public boolean hasEntries(Long transferId) {
        return transferId != null && repository.entryCount(transferId) > 0;
    }

    private Account requireAccount(String accountNo) {
        return accounts.findByAccountNo(accountNo)
                .orElseThrow(() -> new IllegalStateException("missing account " + accountNo));
    }
}