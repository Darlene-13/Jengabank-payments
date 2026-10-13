package com.jengabank.payments.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Writes and reads the append-only audit trail. Hashing, sequencing and locking
 * are done by the {@code audit_chain()} trigger (migration V3), so a compromised
 * application process cannot fork the chain.
 */
@Repository
public class AuditRepository {

    private static final RowMapper<AuditRecord> MAPPER = (rs, rowNum) -> new AuditRecord(
            rs.getLong("sequence"),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("target"),
            rs.getString("details"),
            rs.getString("prev_hash"),
            rs.getString("hash"),
            rs.getTimestamp("ts").toInstant());

    private static final String SELECT_COLUMNS =
            "sequence, actor, action, target, details, prev_hash, hash, ts";

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String actor, String action, String target, String detailsJson) {
        jdbc.update(
                "INSERT INTO audit_log (actor, action, target, details) VALUES (?, ?, ?, ?::jsonb)",
                actor, action, target, detailsJson);
    }

    public Optional<AuditRecord> latest() {
        List<AuditRecord> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM audit_log ORDER BY sequence DESC LIMIT 1",
                MAPPER);
        return rows.stream().findFirst();
    }

    /** Kept for tests and callers that do not use cursors. */
    public List<AuditRecord> recent(int limit) {
        return recent(limit, null);
    }

    /**
     * Cursor-paginated read. {@code cursor == null} starts at the newest record.
     * Otherwise returns records with {@code sequence < cursor}, newest first.
     */
    public List<AuditRecord> recent(int limit, Long cursor) {
        if (cursor == null) {
            return jdbc.query(
                    "SELECT " + SELECT_COLUMNS
                            + " FROM audit_log ORDER BY sequence DESC LIMIT ?",
                    MAPPER, limit);
        }
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM audit_log WHERE sequence < ? ORDER BY sequence DESC LIMIT ?",
                MAPPER, cursor, limit);
    }

    public long countByAction(String action) {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM audit_log WHERE action = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                action);
        return count == null ? 0L : count;
    }

    /** Walks the whole hash chain using the V3 {@code audit_verify()} function. */
    public AuditVerifyResult verifyChain() {
        return jdbc.queryForObject(
                "SELECT valid, checked, broken_at FROM audit_verify()",
                (rs, rowNum) -> new AuditVerifyResult(
                        rs.getBoolean("valid"),
                        rs.getLong("checked"),
                        rs.getObject("broken_at") == null ? null : rs.getLong("broken_at")));
    }

    public record AuditVerifyResult(boolean valid, long checked, Long brokenAt) {}
}