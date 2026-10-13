package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.jayway.jsonpath.JsonPath;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.auth.Role;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.reversal.ReversalService;

class ApprovalAndReversalIT extends AbstractApiIT {

    private static final String REASON = "{\"reason\":\"Customer paid the wrong account\"}";

    @Autowired ReversalService reversals;

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
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
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
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.journalId").isNotEmpty());
        assertThat(balance(alice.accountId())).isEqualTo(1_000_000);
        assertThat(balance(bob.accountId())).isZero();

        postJson("/api/v1/transfers/" + transferId + "/reversals", maker, "{\"reason\":\"Trying a second time\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_EXISTS"));
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
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        assertThat(balance(bob.accountId())).isEqualTo(THRESHOLD);

        String queue = mvc.perform(as(get("/api/v1/reversals?status=PENDING_APPROVAL"), checker))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reversalId = JsonPath.<java.util.List<String>>read(queue,
                "$.items[?(@.transferId == '" + transferId + "')].id").get(0);

        postJson("/api/v1/reversals/" + reversalId + "/approve", checker, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.decidedBy").isNotEmpty());
        assertThat(balance(alice.accountId())).isEqualTo(THRESHOLD * 2);
        assertThat(balance(bob.accountId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?::uuid", String.class, transferId))
                .isEqualTo("REVERSED");
    }

    @Test
    void secondReversalWhileTheFirstIsPendingIsAConflict() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);
        postJson("/api/v1/approvals/" + transferId + "/approve", staff("CHECKER"), "{}").andExpect(status().isOk());
        String maker = staff("MAKER");

        postJson("/api/v1/transfers/" + transferId + "/reversals", maker, REASON).andExpect(status().isAccepted());
        postJson("/api/v1/transfers/" + transferId + "/reversals", maker, REASON)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_EXISTS"));
        assertThat(balance(bob.accountId())).isEqualTo(THRESHOLD);
    }

    @Test
    void requesterCannotApproveTheirOwnReversal() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        String transferId = pendingTransfer(alice, bob, THRESHOLD);
        postJson("/api/v1/approvals/" + transferId + "/approve", staff("CHECKER"), "{}").andExpect(status().isOk());

        UUID makerId = UUID.randomUUID();
        String makerName = createUser("MAKER", makerId);
        String reversalId = id(postJson("/api/v1/transfers/" + transferId + "/reversals", login(makerName), REASON)
                .andExpect(status().isAccepted()));

        // One role per user means the API can't route a maker to approve; prove the service rule holds anyway.
        AuthenticatedUser sameMaker = new AuthenticatedUser(makerId, makerName, "Test", Role.CHECKER, UUID.randomUUID());
        BusinessException refused = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () ->
                reversals.approve(sameMaker, UUID.fromString(reversalId), UUID.randomUUID().toString()));
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.code()).isEqualTo("SELF_APPROVAL");
        assertThat(balance(bob.accountId())).isEqualTo(THRESHOLD);
    }

    @Test
    void dailyLimitResetsAtNairobiMidnight() throws Exception {
        Customer alice = newCustomer(THRESHOLD * 2);
        Customer bob = newCustomer(0);
        UUID aliceId = jdbc.queryForObject("SELECT owner_id FROM accounts WHERE id = ?", UUID.class, alice.accountId());
        String midnight = "(date_trunc('day', now() AT TIME ZONE 'Africa/Nairobi') AT TIME ZONE 'Africa/Nairobi')";
        String insert = "INSERT INTO transfers (from_account_id, to_account_id, amount_minor, status, created_by, created_at) "
                + "SELECT ?, id, 190000000, 'PENDING_APPROVAL', ?, " + midnight + " %s FROM accounts WHERE account_number = ?";

        // KES 1,900,000 one minute before Nairobi midnight belongs to yesterday.
        jdbc.update(insert.formatted("- interval '1 minute'"), alice.accountId(), aliceId, bob.accountNumber());
        transfer(alice, bob, 20_000_000).andExpect(status().isAccepted());

        // The same amount one minute after midnight counts today: 1.9M + 0.2M + 0.2M is over the KES 2,000,000 limit.
        jdbc.update(insert.formatted("+ interval '1 minute'"), alice.accountId(), aliceId, bob.accountNumber());
        transfer(alice, bob, 20_000_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_BLOCKED"))
                .andExpect(jsonPath("$.reasons[0]").value(org.hamcrest.Matchers.startsWith("Above the daily limit")));
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
