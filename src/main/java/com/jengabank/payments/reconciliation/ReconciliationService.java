package com.jengabank.payments.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.audit.AuditService;
import com.jengabank.payments.ledger.LedgerRepository;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-of-day reconciliation. Runs a set of independent proofs over the ledger
 * and writes an append-only row into {@code reconciliation_runs}.
 *
 * <p>Each check is a {@link ReconciliationCheck}; the run is {@code PASSED} only
 * when every check is ok. Results can never be edited or deleted (DB trigger).
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;
    private final LedgerRepository ledger;
    private final AuditService audit;
    private final SecurityEventLogger events;
    private final ObjectMapper json;

    public ReconciliationService(JdbcTemplate jdbc,
                                 LedgerRepository ledger,
                                 AuditService audit,
                                 SecurityEventLogger events,
                                 ObjectMapper json) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.audit = audit;
        this.events = events;
        this.json = json;
    }

    @Transactional
    public ReconciliationReport run(String actor) {
        long start = System.nanoTime();
        List<ReconciliationCheck> checks = new ArrayList<>();

        // 1. Global conservation: sum of all ledger lines must be zero.
        long net = ledger.globalNet();
        checks.add(new ReconciliationCheck(
                "ledger.conservation", net == 0, "sum(amount_minor) = " + net));

        // 2. Every transfer's journal must net to zero.
        long unbalanced = ledger.unbalancedTransferCount();
        checks.add(new ReconciliationCheck(
                "ledger.journals_balanced", unbalanced == 0,
                "unbalanced transfer count = " + unbalanced));

        // 3. COMPLETED transfers must have journal lines.
        long missing = ledger.completedTransfersWithoutEntries();
        checks.add(new ReconciliationCheck(
                "ledger.completed_have_entries", missing == 0,
                "completed without entries = " + missing));

        // 4. Unposted transfers must NOT have journal lines.
        long unposted = ledger.unpostedTransfersWithEntries();
        checks.add(new ReconciliationCheck(
                "ledger.unposted_have_no_entries", unposted == 0,
                "unposted with entries = " + unposted));

        // 5. No customer account overdrawn.
        long overdrawn = ledger.overdrawnCustomerCount();
        checks.add(new ReconciliationCheck(
                "ledger.no_overdrafts", overdrawn == 0,
                "overdrawn customer accounts = " + overdrawn));

        String status = checks.stream().allMatch(ReconciliationCheck::ok) ? "PASSED" : "FAILED";
        boolean passed = "PASSED".equals(status);
        List<String> failed = checks.stream()
                .filter(c -> !c.ok())
                .map(ReconciliationCheck::name)
                .toList();
        long durationMs = (System.nanoTime() - start) / 1_000_000L;

        Long id = jdbc.queryForObject(
                "INSERT INTO reconciliation_runs (status, checks, triggered_by) "
                        + "VALUES (?, ?::jsonb, ?) RETURNING id",
                Long.class, status, toJson(checks), actor);

        Instant runAt = jdbc.queryForObject(
                "SELECT run_at FROM reconciliation_runs WHERE id = ?",
                (rs, n) -> rs.getTimestamp("run_at").toInstant(), id);

        ReconciliationReport report = new ReconciliationReport(
                id, runAt, status, passed, checks, failed, actor, durationMs);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("run_id", id);
        details.put("status", status);
        details.put("failed_checks", failed);
        details.put("triggered_by", actor);
        details.put("duration_ms", durationMs);

        audit.record(actor,
                passed ? Actions.RECONCILIATION_PASS : Actions.RECONCILIATION_FAIL,
                "reconciliation_runs/" + id, details);

        events.emit(passed ? Actions.RECONCILIATION_PASS : Actions.RECONCILIATION_FAIL,
                200, actor, Map.of(
                        "path", "/api/v1/reconciliation/run",
                        "run_id", id,
                        "status", status,
                        "failed_checks", failed,
                        "duration_ms", durationMs));

        return report;
    }

    public ReconciliationReport latest() {
        List<ReconciliationReport> rows = jdbc.query(
                "SELECT id, run_at, status, checks, triggered_by "
                        + "FROM reconciliation_runs ORDER BY id DESC LIMIT 1",
                (rs, n) -> {
                    List<ReconciliationCheck> checks = readChecks(rs.getString("checks"));
                    String status = rs.getString("status");
                    return new ReconciliationReport(
                            rs.getLong("id"),
                            rs.getTimestamp("run_at").toInstant(),
                            status,
                            "PASSED".equals(status),
                            checks,
                            checks.stream().filter(c -> !c.ok())
                                    .map(ReconciliationCheck::name).toList(),
                            rs.getString("triggered_by"),
                            0L);
                });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String toJson(List<ReconciliationCheck> checks) {
        try {
            return json.writeValueAsString(checks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("checks not serialisable", e);
        }
    }

    private List<ReconciliationCheck> readChecks(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            return json.readValue(body, new TypeReference<List<ReconciliationCheck>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}