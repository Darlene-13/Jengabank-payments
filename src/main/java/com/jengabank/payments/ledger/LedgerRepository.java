package com.jengabank.payments.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long transferId, String entryType, Long accountId,
                       long amountMinor, String currency, String memo) {
        jdbc.update(
                "INSERT INTO ledger_entries (transfer_id, entry_type, account_id, amount_minor, currency, memo) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                transferId, entryType, accountId, amountMinor, currency, memo);
    }

    public long globalNet() {
        Long net = jdbc.query(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries",
                rs -> rs.next() ? rs.getLong(1) : null);
        return net == null ? 0L : net;
    }

    public long unbalancedTransferCount() {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM ("
                        + "  SELECT transfer_id FROM ledger_entries WHERE transfer_id IS NOT NULL "
                        + "  GROUP BY transfer_id HAVING COALESCE(SUM(amount_minor), 0) <> 0"
                        + ") unbalanced",
                rs -> rs.next() ? rs.getLong(1) : null);
        return count == null ? 0L : count;
    }

    public long completedTransfersWithoutEntries() {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM transfers t "
                        + "WHERE t.status = 'COMPLETED' "
                        + "AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.transfer_id = t.id)",
                rs -> rs.next() ? rs.getLong(1) : null);
        return count == null ? 0L : count;
    }

    public long unpostedTransfersWithEntries() {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM transfers t "
                        + "WHERE t.status IN ('PENDING_APPROVAL', 'BLOCKED', 'REJECTED') "
                        + "AND EXISTS (SELECT 1 FROM ledger_entries le WHERE le.transfer_id = t.id)",
                rs -> rs.next() ? rs.getLong(1) : null);
        return count == null ? 0L : count;
    }

    public long entryCount(Long transferId) {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                transferId);
        return count == null ? 0L : count;
    }

    public long overdrawnCustomerCount() {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM account_balances WHERE kind = 'CUSTOMER' AND balance_minor < 0",
                rs -> rs.next() ? rs.getLong(1) : null);
        return count == null ? 0L : count;
    }
}