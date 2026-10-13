package com.jengabank.payments.reversal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.jengabank.payments.reversal.ReversalDtos.ReversalDto;

@Repository
public class ReversalRepository {

    public record TransferForReversal(UUID id, UUID fromAccountId, UUID toAccountId, long amountMinor,
                                      String status, UUID journalId, String reference) {}

    public record ReversalRow(UUID id, UUID transferId, String status, UUID requestedBy) {}

    private static final String SELECT_DTO = """
            SELECT r.id, r.transfer_id, r.status, r.reason, t.amount_minor,
                   fa.account_number AS from_number, ta.account_number AS to_number,
                   r.journal_id, ru.username AS requested_by, r.requested_at,
                   du.username AS decided_by, r.decided_at
              FROM reversals r
              JOIN transfers t ON t.id = r.transfer_id
              JOIN accounts fa ON fa.id = t.from_account_id
              JOIN accounts ta ON ta.id = t.to_account_id
              JOIN users ru ON ru.id = r.requested_by
              LEFT JOIN users du ON du.id = r.decided_by
            """;

    private static final RowMapper<ReversalDto> DTO = (rs, n) -> new ReversalDto(
            rs.getObject("id", UUID.class),
            rs.getObject("transfer_id", UUID.class),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getLong("amount_minor"),
            rs.getString("from_number"),
            rs.getString("to_number"),
            rs.getObject("journal_id", UUID.class),
            rs.getString("requested_by"),
            rs.getObject("requested_at", OffsetDateTime.class),
            rs.getString("decided_by"),
            rs.getObject("decided_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public ReversalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TransferForReversal> lockTransfer(UUID transferId) {
        return jdbc.query("""
                SELECT id, from_account_id, to_account_id, amount_minor, status, journal_id, reference
                  FROM transfers WHERE id = ? FOR UPDATE""",
                (rs, n) -> new TransferForReversal(
                        rs.getObject("id", UUID.class),
                        rs.getObject("from_account_id", UUID.class),
                        rs.getObject("to_account_id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getString("status"),
                        rs.getObject("journal_id", UUID.class),
                        rs.getString("reference")),
                transferId).stream().findFirst();
    }

    public boolean hasLiveReversal(UUID transferId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reversals WHERE transfer_id = ? AND status <> 'REJECTED')",
                Boolean.class, transferId);
        return Boolean.TRUE.equals(exists);
    }

    public void insert(UUID id, UUID transferId, String reason, String status, UUID journalId, UUID requestedBy) {
        jdbc.update("""
                INSERT INTO reversals (id, transfer_id, reason, status, journal_id, requested_by)
                VALUES (?, ?, ?, ?, ?, ?)""", id, transferId, reason, status, journalId, requestedBy);
    }

    public Optional<ReversalRow> lockReversal(UUID id) {
        return jdbc.query("SELECT id, transfer_id, status, requested_by FROM reversals WHERE id = ? FOR UPDATE",
                (rs, n) -> new ReversalRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("transfer_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("requested_by", UUID.class)),
                id).stream().findFirst();
    }

    public int markPosted(UUID id, UUID journalId, UUID decidedBy) {
        return jdbc.update("""
                UPDATE reversals SET status = 'POSTED', journal_id = ?, decided_by = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING_APPROVAL'""", journalId, decidedBy, id);
    }

    public int markRejected(UUID id, UUID decidedBy) {
        return jdbc.update("""
                UPDATE reversals SET status = 'REJECTED', decided_by = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING_APPROVAL'""", decidedBy, id);
    }

    public int markTransferReversed(UUID transferId, UUID reversalJournalId) {
        return jdbc.update("""
                UPDATE transfers SET status = 'REVERSED', reversal_journal_id = ?
                 WHERE id = ? AND status = 'COMPLETED'""", reversalJournalId, transferId);
    }

    public Optional<ReversalDto> find(UUID id) {
        return jdbc.query(SELECT_DTO + " WHERE r.id = ?", DTO, id).stream().findFirst();
    }

    public List<ReversalDto> list(String status, OffsetDateTime beforeTs, UUID beforeId, int limit) {
        String where = status == null ? " WHERE TRUE" : " WHERE r.status = ?";
        String order = " ORDER BY r.requested_at DESC, r.id DESC LIMIT ?";
        if (beforeTs == null) {
            return status == null
                    ? jdbc.query(SELECT_DTO + where + order, DTO, limit)
                    : jdbc.query(SELECT_DTO + where + order, DTO, status, limit);
        }
        String keyset = " AND (r.requested_at, r.id) < (?, ?)";
        return status == null
                ? jdbc.query(SELECT_DTO + where + keyset + order, DTO, beforeTs, beforeId, limit)
                : jdbc.query(SELECT_DTO + where + keyset + order, DTO, status, beforeTs, beforeId, limit);
    }
}
