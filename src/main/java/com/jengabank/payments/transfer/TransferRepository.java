package com.jengabank.payments.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.fraud.FraudDecision;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final String SELECT_JOIN =
            "SELECT t.id, t.reference, sa.account_no AS source_account_no, "
                    + "ta.account_no AS target_account_no, "
                    + "t.source_owner_id, t.target_owner_id, "
                    + "t.amount_minor, t.currency, t.status, t.fraud_decision, "
                    + "t.fraud_reasons::text AS fraud_reasons, t.created_by, t.decided_by, "
                    + "t.created_at, t.decided_at "
                    + "FROM transfers t "
                    + "JOIN accounts sa ON sa.id = t.source_account_id "
                    + "JOIN accounts ta ON ta.id = t.target_account_id ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TransferRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = mapper;
    }

    private final RowMapper<Transfer> transferRowMapper = (rs, rowNum) -> new Transfer(
            rs.getLong("id"),
            rs.getString("reference"),
            rs.getString("source_account_no"),
            rs.getString("target_account_no"),
            rs.getString("source_owner_id"),
            rs.getString("target_owner_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            TransferStatus.valueOf(rs.getString("status")),
            FraudDecision.valueOf(rs.getString("fraud_decision")),
            readReasons(rs.getString("fraud_reasons")),
            rs.getString("created_by"),
            rs.getString("decided_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("decided_at")));

    public Long insert(String reference, Long sourceAccountId, Long targetAccountId,
                       String sourceOwnerId, String targetOwnerId, long amountMinor, String currency,
                       TransferStatus status, FraudDecision decision, List<String> reasons,
                       String createdBy) {
        return jdbc.queryForObject(
                "INSERT INTO transfers (reference, source_account_id, target_account_id, "
                        + "source_owner_id, target_owner_id, amount_minor, currency, status, "
                        + "fraud_decision, fraud_reasons, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id",
                Long.class,
                reference, sourceAccountId, targetAccountId, sourceOwnerId, targetOwnerId,
                amountMinor, currency, status.name(), decision.name(), reasonsJson(reasons), createdBy);
    }

    public Optional<Transfer> findById(Long id) {
        return first(jdbc.query(SELECT_JOIN + "WHERE t.id = ?", transferRowMapper, id));
    }

    public Optional<Transfer> findByIdForUpdate(Long id) {
        return first(jdbc.query(SELECT_JOIN + "WHERE t.id = ? FOR UPDATE OF t", transferRowMapper, id));
    }

    public Optional<Transfer> findByReference(String reference) {
        return first(jdbc.query(SELECT_JOIN + "WHERE t.reference = ?", transferRowMapper, reference));
    }

    public void markApproved(Long id, String checkerId) {
        jdbc.update(
                "UPDATE transfers SET status = 'COMPLETED', decided_by = ?, decided_at = now() WHERE id = ?",
                checkerId, id);
    }

    public void markRejected(Long id, String checkerId) {
        jdbc.update(
                "UPDATE transfers SET status = 'REJECTED', decided_by = ?, decided_at = now() WHERE id = ?",
                checkerId, id);
    }

    public long countTransfersBetween(String sourceOwnerId, String targetOwnerId) {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM transfers WHERE source_owner_id = ? AND target_owner_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                sourceOwnerId, targetOwnerId);
        return count == null ? 0L : count;
    }

    public long countBlockedSince(String ownerId, Instant since) {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM transfers WHERE source_owner_id = ? AND status = 'BLOCKED' AND created_at >= ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                ownerId, Timestamp.from(since));
        return count == null ? 0L : count;
    }

    public List<Transfer> findByStatus(String status) {
        return jdbc.query(SELECT_JOIN + "WHERE t.status = ? ORDER BY t.id", transferRowMapper, status);
    }

    public String reasonsJson(List<String> reasons) {
        try {
            return json.writeValueAsString(reasons == null ? List.of() : reasons);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> readReasons(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) return List.of();
        try {
            return json.readValue(jsonString, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of(jsonString);
        }
    }

    private static Optional<Transfer> first(List<Transfer> rows) {
        return rows.stream().findFirst();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}