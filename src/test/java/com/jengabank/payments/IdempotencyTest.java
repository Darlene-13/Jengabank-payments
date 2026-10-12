package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Dev 3: Idempotency-Key handling - the same key with the same payload replays
 * the cached response, the same key with a different payload is a conflict.
 */
@SpringBootTest(properties = {
        "app.odd-hour-start=12",
        "app.odd-hour-end=12",
        "app.large-amount-new-payee-minor=999999999999",
        "app.max-blocked-transfers-24h=-1"
})
class IdempotencyTest extends AbstractPostgresTest {

    private long countTransfersFrom(String accountNo) {
        Long count = jdbc.query(
                "SELECT COUNT(*) FROM transfers t JOIN accounts a ON a.id = t.source_account_id "
                        + "WHERE a.account_no = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                accountNo);
        return count == null ? 0L : count;
    }

    @Test
    void sameKeyAndSamePayloadReplaysTheCachedResponse() throws Exception {
        String source = newAccount("IDEM-S1", 10_000_000L);
        String target = newAccount("IDEM-T1", 0L);
        String key = "idem-" + System.nanoTime();

        String first = mockMvc.perform(transferRequest(source, target, 1_000L, "maker.idem", "MAKER", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = mockMvc.perform(transferRequest(source, target, 1_000L, "maker.idem", "MAKER", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(countTransfersFrom(source)).isEqualTo(1);
        assertThat(balanceOf(source)).isEqualTo(10_000_000L - 1_000L);
        assertThat(balanceOf(target)).isEqualTo(1_000L);
    }

    @Test
    void sameKeyWithADifferentPayloadIsRejected() throws Exception {
        String source = newAccount("IDEM-S2", 10_000_000L);
        String target = newAccount("IDEM-T2", 0L);
        String key = "idem-" + System.nanoTime();

        mockMvc.perform(transferRequest(source, target, 1_000L, "maker.idem", "MAKER", key))
                .andExpect(status().isCreated());

        String stdout = captureStdout(() ->
                mockMvc.perform(transferRequest(source, target, 2_000L, "maker.idem", "MAKER", key))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("idempotency.conflict")));

        assertThat(stdout).contains("\"action\":\"idempotency.conflict\"");
        assertThat(countTransfersFrom(source)).isEqualTo(1);
        assertThat(balanceOf(source)).isEqualTo(10_000_000L - 1_000L);
    }

    @Test
    void withoutAKeyEveryRequestIsANewTransfer() throws Exception {
        String source = newAccount("IDEM-S3", 10_000_000L);
        String target = newAccount("IDEM-T3", 0L);

        mockMvc.perform(transferRequest(source, target, 1_000L, "maker.idem", "MAKER"))
                .andExpect(status().isCreated());
        mockMvc.perform(transferRequest(source, target, 1_000L, "maker.idem", "MAKER"))
                .andExpect(status().isCreated());

        assertThat(countTransfersFrom(source)).isEqualTo(2);
        assertThat(balanceOf(source)).isEqualTo(10_000_000L - 2_000L);
    }
}