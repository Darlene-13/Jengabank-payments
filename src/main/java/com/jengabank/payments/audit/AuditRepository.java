package com.jengabank.payments.audit;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.jengabank.payments.audit.AuditDtos.AuditRecordDto;

/**
 * The app only ever INSERTs (actor, action, target, details). The V3 trigger assigns
 * sequence, ts and the hash chain under a lock; V2 forbids UPDATE, DELETE and TRUNCATE.
 */
@Repository
public class AuditRepository {

    public record ChainCheck(boolean valid, long checked, Long brokenAt) {}

    private static final RowMapper<AuditRecordDto> RECORD = (rs, n) -> new AuditRecordDto(
            rs.getLong("sequence"),
            rs.getObject("ts", OffsetDateTime.class),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("target"),
            rs.getString("hash"),
            rs.getString("prev_hash"));

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String actor, String action, String target, String detailsJson) {
        jdbc.update("INSERT INTO audit_log (actor, action, target, details) VALUES (?, ?, ?, ?::jsonb)",
                actor, action, target, detailsJson);
    }

    /** Newest first. {@code beforeSequence} null = from the top. */
    public List<AuditRecordDto> page(Long beforeSequence, int limit) {
        String columns = "SELECT sequence, ts, actor, action, target, hash, prev_hash FROM audit_log ";
        if (beforeSequence == null) {
            return jdbc.query(columns + "ORDER BY sequence DESC LIMIT ?", RECORD, limit);
        }
        return jdbc.query(columns + "WHERE sequence < ? ORDER BY sequence DESC LIMIT ?", RECORD, beforeSequence, limit);
    }

    /** Walks the whole chain with the V3 audit_verify() function. */
    public ChainCheck verify() {
        return jdbc.queryForObject("SELECT valid, checked, broken_at FROM audit_verify()",
                (rs, n) -> new ChainCheck(rs.getBoolean("valid"), rs.getLong("checked"),
                        (Long) rs.getObject("broken_at")));
    }
}
