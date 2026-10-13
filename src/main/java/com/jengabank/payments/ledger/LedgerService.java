package com.jengabank.payments.ledger;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that writes to the ledger. Every method must run inside the caller's transaction.
 * The database re-checks everything at commit (balanced journal, no overdraft).
 */
@Service
public class LedgerService {

    public enum Direction { DEBIT, CREDIT }

    public record Line(UUID accountId, Direction direction, long amountMinor) {}

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks accounts in id order, so two transfers between the same accounts can never deadlock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccounts(UUID... accountIds) {
        List<UUID> ids = List.of(accountIds).stream().distinct().toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT id FROM accounts WHERE id IN (" + placeholders + ") ORDER BY id FOR NO KEY UPDATE",
                (RowCallbackHandler) rs -> { }, ids.toArray());
    }

    /** Derived balance: always the sum of entries. */
    public long balance(UUID accountId) {
        Long balance = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                  FROM ledger_entries WHERE account_id = ?""", Long.class, accountId);
        return balance == null ? 0 : balance;
    }

    /** Posts one balanced journal. Returns its id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(String kind, String description, UUID reversalOf, UUID createdBy, List<Line> lines) {
        long debits = lines.stream().filter(l -> l.direction() == Direction.DEBIT).mapToLong(Line::amountMinor).sum();
        long credits = lines.stream().filter(l -> l.direction() == Direction.CREDIT).mapToLong(Line::amountMinor).sum();
        if (lines.size() < 2 || debits != credits) {
            throw new IllegalStateException("Refusing to post an unbalanced journal");
        }

        UUID journalId = UUID.randomUUID();
        String safeDescription = description.length() > 140 ? description.substring(0, 140) : description;
        jdbc.update("INSERT INTO journals (id, kind, description, reversal_of, created_by) VALUES (?, ?, ?, ?, ?)",
                journalId, kind, safeDescription, reversalOf, createdBy);
        jdbc.batchUpdate("INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES (?, ?, ?, ?)",
                lines, lines.size(), (ps, line) -> {
                    ps.setObject(1, journalId);
                    ps.setObject(2, line.accountId());
                    ps.setString(3, line.direction().name());
                    ps.setLong(4, line.amountMinor());
                });
        return journalId;
    }
}
