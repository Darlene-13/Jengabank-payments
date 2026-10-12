package com.jengabank.payments.account;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {}

    /** balanceMinor is SUM(ledger entries), calculated on every read. */
    public record AccountDto(UUID id, String accountNumber, String name, String currency,
                             long balanceMinor, String status) {}

    public record LedgerEntryDto(UUID id, UUID journalId, UUID accountId, String direction,
                                 long amountMinor, String currency, String description,
                                 OffsetDateTime createdAt) {}
}
