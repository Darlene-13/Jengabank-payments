package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

class ApprovalAndReversalIT extends AbstractApiIT {

    private String pendingTransfer(Customer from, Customer to, long amount) throws Exception {
        return id(transfer(from, to, amount)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.journalId").doesNotExist()));
    }

    @Test
    void largeTransferWaitsThenMovesMoneyWhenACheckerApproves() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);
        assertThat(balance(alice.accountId())).isEqualTo(THRESHOLD * 2);

        String checker = staff("CHECKER");
        mvc.perform(as(get("/api/v1/approvals"), checker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + transferId + "')]").exists());

        postJson("/api/v1/approvals/" + transferId + "/approve", checker, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.approvedBy").isNotEmpty())
                .andExpect(jsonPath("$.journalId").isNotEmpty());

        assertThat(balance(alice.accountId())).isEqualTo(THRESHOLD);
        assertThat(balance(bob.accountId())).isEqualTo(THRESHOLD);

        postJson("/api/v1/approvals/" + transferId + "/approve", checker, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PENDING"));
    }

    @Test
    void checkerCanRejectWithAReason() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);

        postJson("/api/v1/approvals/" + transferId + "/reject", staff("CHECKER"), "{\"reason\":\"Customer called to cancel\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Customer called to cancel"));
        assertThat(balance(alice.accountId())).isEqualTo(THRESHOLD * 2);
    }

    @Test
    void onlyCheckersCanDecide() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);

        postJson("/api/v1/approvals/" + transferId + "/approve", alice.token(), "{}").andExpect(status().isForbidden());
        postJson("/api/v1/approvals/" + transferId + "/approve", staff("MAKER"), "{}").andExpect(status().isForbidden());
    }

    @Test
    void approvalChecksFundsAgainAtDecisionTime() throws Exception {
        Customer alice = newCustomer(THRESHOLD);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);
        transfer(alice, bob, 1_000).andExpect(status().isCreated()); // now short by 1,000

        postJson("/api/v1/approvals/" + transferId + "/approve", staff("CHECKER"), "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?::uuid", String.class, transferId))
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void smallReversalPostsAtOnceAndOnlyOnce() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        String transferId = id(transfer(alice, bob, 400_000).andExpect(status().isCreated()));
        String maker = staff("MAKER");

        postJson("/api/v1/transfers/" + transferId + "/reversals", maker, "{\"reason\":\"Sent to the wrong account\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.reversalJournalId").isNotEmpty());
        assertThat(balance(alice.accountId())).isEqualTo(1_000_000);
        assertThat(balance(bob.accountId())).isZero();

        postJson("/api/v1/transfers/" + transferId + "/reversals", maker, "{\"reason\":\"Trying a second time\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"));
    }

    @Test
    void reversalNeedsAProperReasonAndAMaker() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        String transferId = id(transfer(alice, bob, 1_000).andExpect(status().isCreated()));

        postJson("/api/v1/transfers/" + transferId + "/reversals", staff("MAKER"), "{\"reason\":\"oops\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/transfers/" + transferId + "/reversals", staff("CHECKER"), "{\"reason\":\"Sent to the wrong account\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void largeReversalWaitsForACheckerWhoIsNotTheMaker() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);
        String checker = staff("CHECKER");
        postJson("/api/v1/approvals/" + transferId + "/approve", checker, "{}").andExpect(status().isOk());

        postJson("/api/v1/transfers/" + transferId + "/reversals", staff("MAKER"), "{\"reason\":\"Confirmed fraud report\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(balance(bob.accountId())).isEqualTo(THRESHOLD);

        String queue = mvc.perform(as(get("/api/v1/approvals/reversals"), checker))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reversalId = JsonPath.<java.util.List<String>>read(queue,
                "$.items[?(@.transferId == '" + transferId + "')].id").get(0);

        postJson("/api/v1/approvals/reversals/" + reversalId + "/approve", checker, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
        assertThat(balance(alice.accountId())).isEqualTo(THRESHOLD * 2);
        assertThat(balance(bob.accountId())).isZero();
    }

    @Test
    void approveReplaysWithTheSameKey() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);
        String checker = staff("CHECKER");
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/approvals/" + transferId + "/approve"), checker)
                            .contentType("application/json").header("Idempotency-Key", key).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }
        assertThat(balance(bob.accountId())).isEqualTo(THRESHOLD);
    }
}
