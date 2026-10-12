package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Dev 3: cumulative limits.
 *
 * <p>Properties shrink the limits so the test data trips them, and disable every
 * fraud rule so the only thing that can reject a transfer is {@code LimitService}.
 */
@SpringBootTest(properties = {
        "app.per-transfer-limit-minor=50000",
        "app.hourly-limit-minor=120000",
        "app.daily-limit-minor=120000",
        "app.weekly-limit-minor=1000000",
        // fraud rules neutralised: only limits may fire here
        "app.odd-hour-start=12",
        "app.odd-hour-end=12",
        "app.large-amount-new-payee-minor=999999999999",
        "app.max-blocked-transfers-24h=-1"
})
class LimitsTest extends AbstractPostgresTest {

    @Test
    void singleTransferAboveThePerTransferLimitIsRejected() throws Exception {
        String source = newAccount("LIM-SRC", 10_000_000L);
        String target = newAccount("LIM-DST", 0L);

        String stdout = captureStdout(() ->
                mockMvc.perform(transferRequest(source, target, 60_000L, "maker.limits", "MAKER"))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.code").value("limit.exceeded")));

        assertThat(stdout).contains("\"action\":\"limit.exceeded\"");
        assertThat(balanceOf(source)).isEqualTo(10_000_000L);
    }

    @Test
    void splittingATransferToEvadeTheDailyLimitIsRejected() throws Exception {
        String source = newAccount("SPLIT-SRC", 10_000_000L);
        String target = newAccount("SPLIT-DST", 0L);

        // Two 45,000 transfers sit under the 50,000 per-transfer limit...
        MvcResult first = createTransfer(source, target, 45_000L, "maker.split", "MAKER");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        MvcResult second = createTransfer(source, target, 45_000L, "maker.split", "MAKER");
        assertThat(second.getResponse().getStatus()).isEqualTo(201);

        // ...the third crosses the 120,000 rolling daily/hourly limit.
        String stdout = captureStdout(() ->
                mockMvc.perform(transferRequest(source, target, 45_000L, "maker.split", "MAKER"))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.code").value("limit.exceeded"))
                        .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("24h"))));

        assertThat(stdout).contains("\"action\":\"limit.exceeded\"");
        // Only the two compliant transfers moved money.
        assertThat(balanceOf(source)).isEqualTo(10_000_000L - 90_000L);
    }

    @Test
    void queuedPendingApprovalsStillCountTowardsTheWindows() throws Exception {
        String source = newAccount("PEND-SRC", 10_000_000L);
        String target = newAccount("PEND-DST", 0L);

        // Money waiting for a checker is still committed money: park 90,000 in a
        // PENDING_APPROVAL transfer (no journal) straight in the table ...
        jdbc.update("INSERT INTO transfers (reference, source_account_id, target_account_id, "
                        + "source_owner_id, target_owner_id, amount_minor, currency, status, "
                        + "fraud_decision, created_by) "
                        + "SELECT 'TRF-QUEUED-' || floor(random() * 1e9)::bigint, sa.id, ta.id, "
                        + "sa.owner_id, ta.owner_id, 90000, 'KES', 'PENDING_APPROVAL', "
                        + "'REVIEW', 'system' "
                        + "FROM accounts sa, accounts ta "
                        + "WHERE sa.account_no = ? AND ta.account_no = ?",
                source, target);

        // ... and prove the window is summed from the transfers table, not from
        // settled balances: the next 45,000 must be refused.
        mockMvc.perform(transferRequest(source, target, 45_000L, "maker.pending", "MAKER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("limit.exceeded"));

        assertThat(balanceOf(source)).isEqualTo(10_000_000L);
    }
}
