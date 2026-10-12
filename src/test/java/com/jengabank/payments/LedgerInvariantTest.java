package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

@SpringBootTest(properties = {
        "app.large-amount-new-payee-minor=1000",
        "app.odd-hour-start=12",
        "app.odd-hour-end=12",
        "app.max-blocked-transfers-24h=-1"
})
class LedgerInvariantTest extends AbstractPostgresTest {

    @Test
    void ledgerNetsToZeroAfterManyTransfers() throws Exception {
        String source = newAccount("INV-SRC", 10_000_000L);
        String target = newAccount("INV-DST", 0L);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(transferRequest(source, target, 500L, "maker.inv", "MAKER"))
                    .andExpect(status().isCreated());
        }

        assertThat(ledgerNet()).isZero();
        assertThat(balanceOf(source)).isEqualTo(10_000_000L - 1_500L);
        assertThat(balanceOf(target)).isEqualTo(1_500L);

        Long unbalanced = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT transfer_id FROM ledger_entries WHERE transfer_id IS NOT NULL "
                        + "GROUP BY transfer_id HAVING COALESCE(SUM(amount_minor), 0) <> 0) x",
                Long.class);
        assertThat(unbalanced).isZero();
    }

    @Test
    void everyCompletedTransferHasExactlyTwoBalancedEntries() throws Exception {
        String source = newAccount("INV2-SRC", 10_000_000L);
        String target = newAccount("INV2-DST", 0L);

        mockMvc.perform(transferRequest(source, target, 750L, "maker.inv", "MAKER"))
                .andExpect(status().isCreated());

        Long transferId = jdbc.queryForObject(
                "SELECT t.id FROM transfers t JOIN accounts a ON a.id = t.source_account_id "
                        + "WHERE a.account_no = ? ORDER BY t.id DESC LIMIT 1",
                Long.class, source);

        assertThat(transferEntryCount(transferId)).isEqualTo(2);
        Long net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE transfer_id = ?",
                Long.class, transferId);
        assertThat(net).isZero();
    }

    @Test
    void transferAwaitingApprovalHasNotMovedMoney() throws Exception {
        String source = newAccount("INV3-SRC", 10_000_000L);
        String target = newAccount("INV3-DST", 0L);
        long netBefore = ledgerNet();

        mockMvc.perform(transferRequest(source, target, 5_000L, "maker.inv", "MAKER"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        Long transferId = jdbc.queryForObject(
                "SELECT t.id FROM transfers t JOIN accounts a ON a.id = t.source_account_id "
                        + "WHERE a.account_no = ? ORDER BY t.id DESC LIMIT 1",
                Long.class, source);

        assertThat(transferEntryCount(transferId)).isZero();
        assertThat(balanceOf(source)).isEqualTo(10_000_000L);
        assertThat(ledgerNet()).isEqualTo(netBefore);
    }

    @Test
    void ledgerEntriesCannotBeRewritten() {
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_entries WHERE id = (SELECT MIN(id) FROM ledger_entries)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount_minor = amount_minor * 10 "
                        + "WHERE id = (SELECT MIN(id) FROM ledger_entries)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void fundsAreCheckedBeforeMoneyMoves() throws Exception {
        String source = newAccount("INV4-SRC", 1_000L);
        String target = newAccount("INV4-DST", 0L);

        mockMvc.perform(transferRequest(source, target, 100_000L, "maker.inv", "MAKER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("transfer.insufficient_funds"));

        assertThat(balanceOf(source)).isEqualTo(1_000L);
        assertThat(ledgerNet()).isZero();
    }
}