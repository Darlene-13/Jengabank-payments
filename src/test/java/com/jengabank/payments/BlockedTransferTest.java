package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.max-blocked-transfers-24h=0",
        "app.odd-hour-start=12",
        "app.odd-hour-end=12",
        "app.large-amount-new-payee-minor=999999999999"
})
class BlockedTransferTest extends AbstractPostgresTest {

    @Test
    void blockedTransferIsPersistedAsEvidenceButMovesNoMoney() throws Exception {
        String source = newAccount("BLK-SRC", 10_000_000L);
        String target = newAccount("BLK-DST", 0L);
        long netBefore = ledgerNet();

        String stdout = captureStdout(() ->
                mockMvc.perform(transferRequest(source, target, 500L, "maker.evil", "MAKER"))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.code").value("fraud.block")));

        assertThat(stdout).contains("\"action\":\"fraud.block\"");

        Long transferId = jdbc.queryForObject(
                "SELECT t.id FROM transfers t JOIN accounts a ON a.id = t.source_account_id "
                        + "WHERE a.account_no = ? ORDER BY t.id DESC LIMIT 1",
                Long.class, source);

        assertThat(statusOf(transferId)).isEqualTo("BLOCKED");
        String decision = jdbc.queryForObject(
                "SELECT fraud_decision FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(decision).isEqualTo("BLOCK");

        String reasons = jdbc.queryForObject(
                "SELECT fraud_reasons::text FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(reasons).contains("fraud.failed_attempts");

        assertThat(transferEntryCount(transferId)).isZero();
        assertThat(balanceOf(source)).isEqualTo(10_000_000L);
        assertThat(balanceOf(target)).isEqualTo(0L);
        assertThat(ledgerNet()).isEqualTo(netBefore);
    }

    @Test
    void blockedTransfersAreAudited() throws Exception {
        String source = newAccount("BLK2-SRC", 10_000_000L);
        String target = newAccount("BLK2-DST", 0L);

        mockMvc.perform(transferRequest(source, target, 500L, "maker.evil", "MAKER"))
                .andExpect(status().isUnprocessableEntity());

        Long auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'fraud.block'", Long.class);
        assertThat(auditRows).isGreaterThanOrEqualTo(1);

        Long rowsMissingChain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE seq IS NULL OR hash IS NULL", Long.class);
        assertThat(rowsMissingChain).isZero();
    }
}