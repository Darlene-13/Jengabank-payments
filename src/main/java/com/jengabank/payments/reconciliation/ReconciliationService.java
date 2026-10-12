package com.jengabank.payments.reconciliation;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.audit.AuditService;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;

/**
 * End-of-day reconciliation: independent proofs over the ledger, each a plain SQL
 * count that must be zero. The checks run in one read-only REPEATABLE READ snapshot so
 * they all see the same books. The result and its audit record are written afterwards
 * in a normal READ COMMITTED transaction: the audit trigger must see the latest chain
 * head, which an older snapshot would hide. Results are append-only (V2 trigger).
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final SecurityEventLogger events;
    private final ObjectMapper mapper;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate write;

    public ReconciliationService(JdbcTemplate jdbc, AuditService audit, SecurityEventLogger events, ObjectMapper mapper,
                                 PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.mapper = mapper;
        this.snapshot = new TransactionTemplate(txManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.write = new TransactionTemplate(txManager);
    }

    /** {@code runBy} null = the scheduled job. */
    public ReconciliationRun run(UUID runBy, String actor) {
        List<ReconciliationCheck> checks = snapshot.execute(tx -> checks());
        return write.execute(tx -> record(checks, runBy, actor));
    }

    private List<ReconciliationCheck> checks() {
        List<ReconciliationCheck> checks = new ArrayList<>();

        long net = single("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                  FROM ledger_entries""");
        checks.add(new ReconciliationCheck("ledger.conservation", net == 0,
                "credits minus debits across the whole ledger = " + net));

        long unbalanced = single("""
                SELECT COUNT(*) FROM (
                  SELECT journal_id FROM ledger_entries GROUP BY journal_id
                  HAVING SUM(amount_minor) FILTER (WHERE direction = 'DEBIT')
                         IS DISTINCT FROM SUM(amount_minor) FILTER (WHERE direction = 'CREDIT')
                      OR COUNT(*) < 2) j""");
        checks.add(new ReconciliationCheck("ledger.journals_balanced", unbalanced == 0,
                "journals where debits <> credits = " + unbalanced));

        long mismatched = single("""
                SELECT COUNT(*) FROM transfers t
                 WHERE t.journal_id IS NOT NULL
                   AND NOT (EXISTS (SELECT 1 FROM ledger_entries e WHERE e.journal_id = t.journal_id
                                      AND e.account_id = t.from_account_id AND e.direction = 'DEBIT'
                                      AND e.amount_minor = t.amount_minor)
                        AND EXISTS (SELECT 1 FROM ledger_entries e WHERE e.journal_id = t.journal_id
                                      AND e.account_id = t.to_account_id AND e.direction = 'CREDIT'
                                      AND e.amount_minor = t.amount_minor))""");
        checks.add(new ReconciliationCheck("transfers.match_journals", mismatched == 0,
                "posted transfers whose journal doesn't match = " + mismatched));

        long badReversals = single("""
                SELECT COUNT(*) FROM transfers t
                  LEFT JOIN journals r ON r.id = t.reversal_journal_id
                 WHERE t.status = 'REVERSED'
                   AND (r.id IS NULL OR r.kind <> 'REVERSAL' OR r.reversal_of IS DISTINCT FROM t.journal_id)""");
        checks.add(new ReconciliationCheck("reversals.linked", badReversals == 0,
                "reversed transfers without a matching reversal journal = " + badReversals));

        long overdrawn = single("""
                SELECT COUNT(*) FROM account_balances b JOIN accounts a ON a.id = b.account_id
                 WHERE a.allow_negative = FALSE AND b.balance_minor < 0""");
        checks.add(new ReconciliationCheck("accounts.no_overdrafts", overdrawn == 0,
                "accounts below zero = " + overdrawn));

        Map<String, Object> chain = jdbc.queryForMap("SELECT valid, checked, broken_at FROM audit_verify()");
        boolean chainValid = Boolean.TRUE.equals(chain.get("valid"));
        checks.add(new ReconciliationCheck("audit.chain_intact", chainValid,
                chainValid ? "records checked = " + chain.get("checked") : "chain broken at sequence " + chain.get("broken_at")));
        return checks;
    }

    private ReconciliationRun record(List<ReconciliationCheck> checks, UUID runBy, String actor) {
        boolean passed = checks.stream().allMatch(ReconciliationCheck::passed);
        String status = passed ? "PASSED" : "FAILED";
        List<String> failed = checks.stream().filter(c -> !c.passed()).map(ReconciliationCheck::name).toList();

        Map<String, Object> row = jdbc.queryForMap(
                "INSERT INTO reconciliation_runs (status, checks, run_by) VALUES (?, ?::jsonb, ?) RETURNING id, run_at",
                status, json(checks), runBy);
        UUID id = (UUID) row.get("id");

        String action = passed ? Actions.RECONCILIATION_PASSED : Actions.RECONCILIATION_FAILED;
        audit.record(actor, action, "reconciliation:" + id, Map.of("failed_checks", failed));
        if (passed) {
            events.info(action, "username", actor, "run_id", id);
        } else {
            events.error(action, "username", actor, "run_id", id, "failed_checks", failed);
        }
        return latestById(id);
    }

    public Optional<ReconciliationRun> latest() {
        return jdbc.query("SELECT id, run_at, status, checks::text AS checks FROM reconciliation_runs "
                        + "ORDER BY run_at DESC, id DESC LIMIT 1",
                (rs, n) -> new ReconciliationRun(rs.getObject("id", UUID.class),
                        rs.getObject("run_at", OffsetDateTime.class), rs.getString("status"),
                        readChecks(rs.getString("checks")))).stream().findFirst();
    }

    private ReconciliationRun latestById(UUID id) {
        return jdbc.queryForObject("SELECT id, run_at, status, checks::text AS checks FROM reconciliation_runs WHERE id = ?",
                (rs, n) -> new ReconciliationRun(rs.getObject("id", UUID.class),
                        rs.getObject("run_at", OffsetDateTime.class), rs.getString("status"),
                        readChecks(rs.getString("checks"))), id);
    }

    private long single(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private String json(List<ReconciliationCheck> checks) {
        try {
            return mapper.writeValueAsString(checks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ReconciliationCheck> readChecks(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<ReconciliationCheck>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored reconciliation checks are not readable", e);
        }
    }
}
