package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

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
    void missingIdempotencyKeyIsRejected() throws Exception {
        String source = newAccount("IDEM-S3", 10_000_000L);
        String target = newAccount("IDEM-T3", 0L);

        // Build the request manually so we can omit the Idempotency-Key header.
        String stdout = captureStdout(() ->
                mockMvc.perform(post("/api/v1/transfers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", "maker.idem")
                                .header("X-User-Role", "MAKER")
                                .content(mapper.writeValueAsString(
                                        new com.jengabank.payments.transfer.TransferRequest(
                                                source, target, 1_000L, "KES"))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("request.invalid")));

        assertThat(stdout).contains("\"action\":\"request.invalid\"");
        assertThat(countTransfersFrom(source)).isZero();
        assertThat(balanceOf(source)).isEqualTo(10_000_000L);
    }
}