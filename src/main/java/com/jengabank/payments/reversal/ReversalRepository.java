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

    /** The columns the money path needs. */
    public record Row(UUID id, UUID transferId, String status, UUID requestedBy, String requestedByUsername, String reason) {}

    private static final String DTO_SELECT = """
            SELECT r.id, r.transfer_id, fa.account_number AS from_number, ta.account_number AS to_number,
                   t.amount_minor, r.reason, r.status, u.username, r.requested_at
              FROM reversals r
              JOIN transfers t ON t.id = r.transfer_id
              JOIN accounts fa ON fa.id = t.from_account_id
              JOIN accounts ta ON ta.id = t.to_account_id
              JOIN users u ON u.id = r.requested_by
            """;

    private static final RowMapper<ReversalDto> DTO = (rs, n) -> new ReversalDto(
            rs.getObject("id", UUID.class),
            rs.getObject("transfer_id", UUID.class),
            rs.getString("from_number"),
            rs.getString("to_number"),
            rs.getLong("amount_minor"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("username"),
            rs.getObject("requested_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public ReversalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Pending or posted. The uq_reversal_live index allows at most one per transfer. */
    public boolean hasLive(UUID transferId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM reversals WHERE transfer_id = ? AND status <> 'REJECTED')",
                Boolean.class, transferId));
    }

    public UUID insertPosted(UUID transferId, String reason, UUID journalId, UUID requestedBy) {
        return jdbc.queryForObject("""
                INSERT INTO reversals (transfer_id, reason, status, journal_id, requested_by)
                VALUES (?, ?, 'POSTED', ?, ?) RETURNING id
                """, UUID.class, transferId, reason, journalId, requestedBy);
    }

    public UUID insertPending(UUID transferId, String reason, UUID requestedBy) {
        return jdbc.queryForObject("""
                INSERT INTO reversals (transfer_id, reason, status, requested_by)
                VALUES (?, ?, 'PENDING_APPROVAL', ?) RETURNING id
                """, UUID.class, transferId, reason, requestedBy);
    }

    public Optional<Row> findForUpdate(UUID id) {
        return jdbc.query("""
                SELECT r.id, r.transfer_id, r.status, r.requested_by, u.username, r.reason
                  FROM reversals r JOIN users u ON u.id = r.requested_by
                 WHERE r.id = ? FOR UPDATE OF r
                """,
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("transfer_id", UUID.class),
                        rs.getString("status"), rs.getObject("requested_by", UUID.class),
                        rs.getString("username"), rs.getString("reason")),
                id).stream().findFirst();
    }

    public void markPosted(UUID id, UUID journalId, UUID checkerId) {
        jdbc.update("""
                UPDATE reversals SET status = 'POSTED', journal_id = ?, decided_by = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, journalId, checkerId, id);
    }

    public void markRejected(UUID id, UUID checkerId) {
        jdbc.update("""
                UPDATE reversals SET status = 'REJECTED', decided_by = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, checkerId, id);
    }

    public ReversalDto dto(UUID id) {
        return jdbc.queryForObject(DTO_SELECT + " WHERE r.id = ?", DTO, id);
    }

    /** Oldest first, so the queue is worked in order. Cursor = (requested_at, id). */
    public List<ReversalDto> pending(OffsetDateTime afterTs, UUID afterId, int limit) {
        if (afterTs == null) {
            return jdbc.query(DTO_SELECT + " WHERE r.status = 'PENDING_APPROVAL' ORDER BY r.requested_at, r.id LIMIT ?",
                    DTO, limit);
        }
        return jdbc.query(DTO_SELECT + """
                 WHERE r.status = 'PENDING_APPROVAL' AND (r.requested_at, r.id) > (?, ?)
                 ORDER BY r.requested_at, r.id LIMIT ?""", DTO, afterTs, afterId, limit);
    }
}
