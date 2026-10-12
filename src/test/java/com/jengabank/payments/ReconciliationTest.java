package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

/**
 * Dev 3: end-of-day reconciliation proves the books independently of the
 * application code, and its results can never be rewritten.
 */
@SpringBootTest(properties = {
        "app.odd-hour-start=12",
        "app.odd-hour-end=12",
        "app.large-amount-new-payee-minor=999999999999",
        "app.max-blocked-transfers-24h=-1"
})
class ReconciliationTest extends AbstractPostgresTest {

    private void runOnce(String expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/reconciliation/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "auditor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @Test
    void balancedBookPasses() throws Exception {
        runOnce("PASSED");

        mockMvc.perform(get("/api/v1/reconciliation/runs/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void moneyCreatedFromThinAirIsDetectedAndCanOnlyBeCorrectedByAppending() throws Exception {
        long netBefore = ledgerNet();
        assertThat(netBefore).isZero();

        jdbc.update("INSERT INTO ledger_entries (transfer_id, entry_type, account_id, amount_minor, currency, memo) "
                + "SELECT NULL, 'MANUAL', id, 12345, 'KES', 'tamper' FROM accounts WHERE account_no = '9000'");

        mockMvc.perform(post("/api/v1/reconciliation/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "auditor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedChecks[0]").value("ledger.conservation"));

        mockMvc.perform(get("/api/v1/reconciliation/runs/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        jdbc.update("INSERT INTO ledger_entries (transfer_id, entry_type, account_id, amount_minor, currency, memo) "
                + "SELECT NULL, 'MANUAL', id, -12345, 'KES', 'correction' FROM accounts WHERE account_no = '9000'");

        assertThat(ledgerNet()).isZero();
        runOnce("PASSED");
    }

    @Test
    void recordedRunsAreImmutable() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reconciliation_runs SET status = 'PASSED' WHERE id = (SELECT MIN(id) FROM reconciliation_runs)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM reconciliation_runs WHERE id = (SELECT MIN(id) FROM reconciliation_runs)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditLogIsImmutable() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_log SET action = 'nothing.happened' WHERE seq = (SELECT MIN(seq) FROM audit_log)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit_log WHERE seq = (SELECT MIN(seq) FROM audit_log)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reconciliationWritesAnAuditTrail() throws Exception {
        runOnce("PASSED");

        Long passes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action IN ('reconciliation.passed', 'reconciliation.failed')",
                Long.class);
        assertThat(passes).isGreaterThanOrEqualTo(1);
    }
}