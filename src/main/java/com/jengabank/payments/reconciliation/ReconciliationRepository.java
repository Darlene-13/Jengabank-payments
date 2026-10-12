package com.jengabank.payments.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores reconciliation runs. The V2 append-only trigger makes past runs
 * immutable: results can be added, never rewritten.
 */
@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ReconciliationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Long insertRun(String status, List<ReconciliationCheck> checks, String triggeredBy) {
        String json;
        try {
            json = mapper.writeValueAsString(checks);
        } catch (JsonProcessingException e) {
            json = "[]";
        }
        return jdbc.queryForObject(
                "INSERT INTO reconciliation_runs (status, checks, triggered_by) VALUES (?, ?::jsonb, ?) RETURNING id",
                Long.class, status, json, triggeredBy);
    }

    public Optional<ReconciliationReport> latest() {
        List<ReconciliationReport> rows = jdbc.query(
                "SELECT id, status, checks::text AS checks, run_at FROM reconciliation_runs "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> new ReconciliationReport(
                        rs.getLong("id"),
                        rs.getString("status"),
                        readChecks(rs.getString("checks")),
                        toInstant(rs.getTimestamp("run_at")),
                        0L));
        return rows.stream().findFirst();
    }

    public long countByStatus(String status) {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM reconciliation_runs WHERE status = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                status);
        return count == null ? 0L : count;
    }

    private List<ReconciliationCheck> readChecks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<ReconciliationCheck>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
