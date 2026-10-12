package com.jengabank.payments.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private static final String ACCOUNT_COLUMNS = "id, account_number, owner_id, type, status";

    private static final RowMapper<LedgerAccount> ACCOUNT = (rs, n) -> new LedgerAccount(
            rs.getObject("id", UUID.class),
            rs.getString("account_number"),
            rs.getObject("owner_id", UUID.class),
            rs.getString("type"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LedgerAccount> findById(UUID id) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id = ?", ACCOUNT, id).stream().findFirst();
    }

    public Optional<LedgerAccount> findByNumber(String accountNumber) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE account_number = ?",
                ACCOUNT, accountNumber).stream().findFirst();
    }

    /**
     * Row-locks both accounts in id order, so two transfers between the same pair
     * (in either direction) queue up instead of deadlocking.
     */
    public List<LedgerAccount> lock(UUID a, UUID b) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                ACCOUNT, a, b);
    }

    public long balance(UUID accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT balance_minor FROM account_balances WHERE account_id = ?", Long.class, accountId);
        return balance == null ? 0L : balance;
    }

    public UUID insertJournal(String kind, String description, UUID reversalOf, UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO journals (kind, description, reversal_of, created_by)
                VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, kind, description, reversalOf, createdBy);
    }

    public void insertEntry(UUID journalId, UUID accountId, String direction, long amountMinor) {
        jdbc.update("INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES (?, ?, ?, ?)",
                journalId, accountId, direction, amountMinor);
    }
}
