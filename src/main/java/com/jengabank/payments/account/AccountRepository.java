package com.jengabank.payments.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.jengabank.payments.account.AccountDtos.AccountDto;
import com.jengabank.payments.account.AccountDtos.LedgerEntryDto;

@Repository
public class AccountRepository {

    // Balance is always derived from the ledger. There is no balance column.
    private static final String BALANCE = """
            (SELECT COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
               FROM ledger_entries e WHERE e.account_id = a.id)""";

    private static final String ACCOUNT_COLUMNS =
            "a.id, a.account_number, a.name, a.currency, a.status, " + BALANCE + " AS balance_minor";

    private static final RowMapper<AccountDto> ACCOUNT = (rs, n) -> new AccountDto(
            rs.getObject("id", UUID.class),
            rs.getString("account_number"),
            rs.getString("name"),
            rs.getString("currency"),
            rs.getLong("balance_minor"),
            rs.getString("status"));

    private static final RowMapper<LedgerEntryDto> ENTRY = (rs, n) -> new LedgerEntryDto(
            rs.getObject("id", UUID.class),
            rs.getObject("journal_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("direction"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("description"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Owner is part of every WHERE clause: you can only ever load your own accounts. */
    public List<AccountDto> listOwned(UUID ownerId, String afterAccountNumber, int limit) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + """
                 FROM accounts a
                WHERE a.owner_id = ? AND a.account_number > ?
                ORDER BY a.account_number
                LIMIT ?""", ACCOUNT, ownerId, afterAccountNumber == null ? "" : afterAccountNumber, limit);
    }

    public Optional<AccountDto> findOwned(UUID accountId, UUID ownerId) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + """
                 FROM accounts a
                WHERE a.id = ? AND a.owner_id = ?""", ACCOUNT, accountId, ownerId).stream().findFirst();
    }

    public List<LedgerEntryDto> entries(UUID accountId, OffsetDateTime beforeTs, UUID beforeId, int limit) {
        String base = """
                SELECT e.id, e.journal_id, e.account_id, e.direction, e.amount_minor, e.currency,
                       j.description, e.created_at
                  FROM ledger_entries e
                  JOIN journals j ON j.id = e.journal_id
                 WHERE e.account_id = ?
                """;
        if (beforeTs == null) {
            return jdbc.query(base + " ORDER BY e.created_at DESC, e.id DESC LIMIT ?", ENTRY, accountId, limit);
        }
        return jdbc.query(base + " AND (e.created_at, e.id) < (?, ?) ORDER BY e.created_at DESC, e.id DESC LIMIT ?",
                ENTRY, accountId, beforeTs, beforeId, limit);
    }
}
