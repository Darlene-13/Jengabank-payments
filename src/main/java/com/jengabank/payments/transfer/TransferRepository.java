package com.jengabank.payments.transfer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.fraud.FraudDecision;

@Repository
public class TransferRepository {

    private static final String SELECT = """
            SELECT t.id, t.from_account_id, fa.account_number AS from_number, fa.owner_id AS from_owner,
                   t.to_account_id, ta.account_number AS to_number, ta.owner_id AS to_owner,
                   t.amount_minor, t.currency, t.reference, t.status, t.fraud_decision,
                   t.fraud_reasons::text AS fraud_reasons, t.journal_id, t.reversal_journal_id,
                   t.created_by, cu.username AS created_by_username, t.created_at,
                   t.decided_by, du.username AS decided_by_username, t.decided_at, t.rejection_reason
              FROM transfers t
              JOIN accounts fa ON fa.id = t.from_account_id
              JOIN accounts ta ON ta.id = t.to_account_id
              JOIN users cu ON cu.id = t.created_by
              LEFT JOIN users du ON du.id = t.decided_by
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<Transfer> rowMapper;

    public TransferRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, n) -> new Transfer(
                rs.getObject("id", UUID.class),
                rs.getObject("from_account_id", UUID.class),
                rs.getString("from_number"),
                rs.getObject("from_owner", UUID.class),
                rs.getObject("to_account_id", UUID.class),
                rs.getString("to_number"),
                rs.getObject("to_owner", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("reference"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getString("fraud_decision") == null ? null : FraudDecision.valueOf(rs.getString("fraud_decision")),
                readReasons(rs.getString("fraud_reasons")),
                rs.getObject("journal_id", UUID.class),
                rs.getObject("reversal_journal_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_username"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("decided_by", UUID.class),
                rs.getString("decided_by_username"),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getString("rejection_reason"));
    }

    /** Serialises one customer's transfer creation, so two parallel requests can't both pass the limits. */
    public void lockUser(UUID userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('jengabank.transfers:' || ?::text))",
                rs -> null, userId);
    }

    public UUID insert(UUID from, UUID to, long amountMinor, String reference, TransferStatus status,
                       FraudDecision decision, List<String> reasons, UUID journalId, UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO transfers (from_account_id, to_account_id, amount_minor, reference, status,
                                       fraud_decision, fraud_reasons, journal_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) RETURNING id
                """, UUID.class, from, to, amountMinor, reference, status.name(), decision.name(),
                json(reasons), journalId, createdBy);
    }

    public Optional<Transfer> find(UUID id) {
        return jdbc.query(SELECT + " WHERE t.id = ?", rowMapper, id).stream().findFirst();
    }

    public Optional<Transfer> findForUpdate(UUID id) {
        return jdbc.query(SELECT + " WHERE t.id = ? FOR UPDATE OF t", rowMapper, id).stream().findFirst();
    }

    /**
     * Newest first. {@code visibleTo} null = staff (everything); otherwise only transfers
     * touching one of that customer's accounts.
     */
    public List<Transfer> page(UUID visibleTo, TransferStatus status, OffsetDateTime beforeTs, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (visibleTo != null) {
            sql.append(" AND (fa.owner_id = ? OR ta.owner_id = ?)");
            args.add(visibleTo);
            args.add(visibleTo);
        }
        if (status != null) {
            sql.append(" AND t.status = ?");
            args.add(status.name());
        }
        if (beforeTs != null) {
            sql.append(" AND (t.created_at, t.id) < (?, ?)");
            args.add(beforeTs);
            args.add(beforeId);
        }
        sql.append(" ORDER BY t.created_at DESC, t.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), rowMapper, args.toArray());
    }

    public boolean hasPaidBefore(UUID userId, UUID toAccountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transfers
                                WHERE created_by = ? AND to_account_id = ? AND status IN ('COMPLETED', 'REVERSED'))
                """, Boolean.class, userId, toAccountId));
    }

    public int blockedInLast24h(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transfers
                 WHERE created_by = ? AND status = 'BLOCKED' AND created_at >= now() - interval '24 hours'
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    public void markCompleted(UUID id, UUID journalId, UUID checkerId) {
        jdbc.update("""
                UPDATE transfers SET status = 'COMPLETED', journal_id = ?, decided_by = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, journalId, checkerId, id);
    }

    public void markRejected(UUID id, UUID checkerId, String reason) {
        jdbc.update("""
                UPDATE transfers SET status = 'REJECTED', rejection_reason = ?, decided_by = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING_APPROVAL'
                """, reason, checkerId, id);
    }

    public void markReversed(UUID id, UUID reversalJournalId) {
        jdbc.update("UPDATE transfers SET status = 'REVERSED', reversal_journal_id = ? WHERE id = ? AND status = 'COMPLETED'",
                reversalJournalId, id);
    }

    private String json(List<String> reasons) {
        try {
            return mapper.writeValueAsString(reasons == null ? List.of() : reasons);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> readReasons(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("fraud_reasons is not a JSON array", e);
        }
    }
}
