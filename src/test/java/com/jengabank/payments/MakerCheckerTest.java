package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jengabank.payments.transfer.TransferResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.large-amount-new-payee-minor=1000",
        "app.odd-hour-start=12",
        "app.odd-hour-end=12",
        "app.max-blocked-transfers-24h=-1"
})
class MakerCheckerTest extends AbstractPostgresTest {

    private long queueTransfer(String source, String target, long amount, String maker) throws Exception {
        MvcResult result = mockMvc.perform(transferRequest(source, target, amount, maker, "MAKER"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        TransferResponse response = mapper.readValue(
                result.getResponse().getContentAsString(), TransferResponse.class);
        return response.id();
    }

    @Test
    void makerCannotApproveTheirOwnTransfer() throws Exception {
        String source = newAccount("MC-SRC", 10_000_000L);
        String target = newAccount("MC-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        String stdout = captureStdout(() ->
                mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", "maker.jane")
                                .header("X-User-Role", "CHECKER"))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("approval.self_approval_denied")));

        assertThat(stdout).contains("\"action\":\"approval.self_approval_denied\"");
        assertThat(statusOf(transferId)).isEqualTo("PENDING_APPROVAL");
        assertThat(transferEntryCount(transferId)).isZero();
        assertThat(balanceOf(source)).isEqualTo(10_000_000L);
    }

    @Test
    void databaseRejectsSelfApprovalEvenIfJavaWereBypassed() throws Exception {
        String source = newAccount("DB-SRC", 10_000_000L);
        String target = newAccount("DB-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.mallory");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE transfers SET decided_by = created_by, status = 'COMPLETED' WHERE id = ?", transferId))
                .isInstanceOf(DataAccessException.class);

        assertThat(statusOf(transferId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void differentCheckerApprovesAndMoneyMovesExactlyOnce() throws Exception {
        String source = newAccount("OK-SRC", 10_000_000L);
        String target = newAccount("OK-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        String stdout = captureStdout(() ->
                mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", "checker.bob")
                                .header("X-User-Role", "CHECKER")
                                .header("Idempotency-Key", "approve-" + transferId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPLETED"))
                        .andExpect(jsonPath("$.decidedBy").value("checker.bob")));

        assertThat(stdout).contains("\"action\":\"approval.approved\"");
        assertThat(statusOf(transferId)).isEqualTo("COMPLETED");
        assertThat(balanceOf(source)).isEqualTo(10_000_000L - 5_000L);
        assertThat(balanceOf(target)).isEqualTo(5_000L);
        assertThat(transferEntryCount(transferId)).isEqualTo(2);
    }

    @Test
    void approvalIsIdempotentForTheSameChecker() throws Exception {
        String source = newAccount("IDEM-SRC", 10_000_000L);
        String target = newAccount("IDEM-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        String key = "checker-approve-" + transferId;
        MvcResult first = mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "checker.bob")
                        .header("X-User-Role", "CHECKER")
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult replay = mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "checker.bob")
                        .header("X-User-Role", "CHECKER")
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(balanceOf(source)).isEqualTo(10_000_000L - 5_000L);
        assertThat(transferEntryCount(transferId)).isEqualTo(2);
    }

    @Test
    void nonCheckerRolesAreRejected() throws Exception {
        String source = newAccount("ROLE-SRC", 10_000_000L);
        String target = newAccount("ROLE-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "maker.jane")
                        .header("X-User-Role", "MAKER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("authz.access.denied"));

        assertThat(statusOf(transferId)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void rejectionLeavesTheLedgerUntouched() throws Exception {
        String source = newAccount("REJ-SRC", 10_000_000L);
        String target = newAccount("REJ-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        mockMvc.perform(post("/api/v1/approvals/{id}/reject", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "checker.bob")
                        .header("X-User-Role", "CHECKER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decidedBy").value("checker.bob"));

        assertThat(statusOf(transferId)).isEqualTo("REJECTED");
        assertThat(transferEntryCount(transferId)).isZero();
        assertThat(balanceOf(source)).isEqualTo(10_000_000L);
    }

    @Test
    void decidingAnAlreadyDecidedTransferIsAConflict() throws Exception {
        String source = newAccount("CONF-SRC", 10_000_000L);
        String target = newAccount("CONF-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        mockMvc.perform(post("/api/v1/approvals/{id}/reject", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "checker.bob")
                        .header("X-User-Role", "CHECKER"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "checker.carol")
                        .header("X-User-Role", "CHECKER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("approval.invalid_state"));

        assertThat(statusOf(transferId)).isEqualTo("REJECTED");
        assertThat(transferEntryCount(transferId)).isZero();
    }

    @Test
    void pendingQueueIsVisibleToCheckers() throws Exception {
        String source = newAccount("QUEUE-SRC", 10_000_000L);
        String target = newAccount("QUEUE-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + transferId + ")]").exists());
    }

    @Test
    void auditTrailRecordsTheDecision() throws Exception {
        String source = newAccount("AUD-SRC", 10_000_000L);
        String target = newAccount("AUD-DST", 0L);
        long transferId = queueTransfer(source, target, 5_000L, "maker.jane");

        mockMvc.perform(post("/api/v1/approvals/{id}/approve", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "checker.bob")
                        .header("X-User-Role", "CHECKER"))
                .andExpect(status().isOk());

        Long seq = jdbc.query(
                "SELECT seq FROM audit_log WHERE action = 'approval.approved' AND target = ("
                        + " SELECT reference FROM transfers WHERE id = ?) ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                transferId);
        assertThat(seq).isNotNull();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prev_hash, hash FROM audit_log WHERE seq = ?", seq);
        assertThat(row.get("hash").toString()).hasSize(64);
        assertThat(row.get("prev_hash").toString()).hasSize(64);
    }
}