package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TransferFlowIT extends AbstractApiIT {

    @Test
    void completedTransferMovesMoneyThroughTheLedger() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);

        transfer(alice, bob, 250_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fraud.decision").value("ALLOW"))
                .andExpect(jsonPath("$.journalId").isNotEmpty())
                .andExpect(jsonPath("$.fromAccountNumber").value(alice.accountNumber()))
                .andExpect(jsonPath("$.toAccountNumber").value(bob.accountNumber()))
                .andExpect(jsonPath("$.createdBy").value(alice.username()));

        assertThat(balance(alice.accountId())).isEqualTo(750_000);
        assertThat(balance(bob.accountId())).isEqualTo(250_000);
    }

    @Test
    void sameIdempotencyKeyReplaysInsteadOfPayingTwice() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        String key = UUID.randomUUID().toString();

        String first = id(transfer(alice, bob.accountNumber(), 100_000, key).andExpect(status().isCreated()));
        String second = id(transfer(alice, bob.accountNumber(), 100_000, key).andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        assertThat(balance(alice.accountId())).isEqualTo(900_000);
    }

    @Test
    void sameKeyWithADifferentBodyIsAConflict() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        String key = UUID.randomUUID().toString();

        transfer(alice, bob.accountNumber(), 100_000, key).andExpect(status().isCreated());
        transfer(alice, bob.accountNumber(), 999_000, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(balance(alice.accountId())).isEqualTo(900_000);
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        transfer(alice, bob.accountNumber(), 100_000, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        assertThat(balance(alice.accountId())).isEqualTo(1_000_000);
    }

    @Test
    void cannotSendFromSomeoneElsesAccount() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer mallory = newCustomer(0);
        Customer thief = new Customer(mallory.username(), mallory.token(), alice.accountId(), alice.accountNumber());

        transfer(thief, mallory, 100_000).andExpect(status().isNotFound());
        assertThat(balance(alice.accountId())).isEqualTo(1_000_000);
    }

    @Test
    void insufficientFundsIsRefusedAndAudited() throws Exception {
        Customer alice = newCustomer(50_000);
        Customer bob = newCustomer(0);

        transfer(alice, bob, 50_001)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.requestId").exists());

        assertThat(balance(alice.accountId())).isEqualTo(50_000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE actor = ? AND action = 'transfer.insufficient_funds'",
                Integer.class, alice.username())).isEqualTo(1);
    }

    @Test
    void overThePerTransferLimitIsRefused() throws Exception {
        Customer alice = newCustomer(200_000_000);
        Customer bob = newCustomer(0);
        transfer(alice, bob, 100_000_001)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_BLOCKED"))
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.startsWith("Above the single-transfer limit"))));
    }

    @Test
    void sixthTransferInAMinuteHitsTheVelocityLimit() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        for (int i = 0; i < 5; i++) {
            transfer(alice, bob, 1_000).andExpect(status().isCreated());
        }
        transfer(alice, bob, 1_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_BLOCKED"))
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.startsWith("Too many transfers"))));
    }

    @Test
    void reachingTheDailyLimitIsBlockedAndKeptAsEvidence() throws Exception {
        // Pending transfers count toward the daily total: KES 1,000,000 + 600,000 wait for a checker,
        // then KES 500,000 more would take today to 2,100,000, over the KES 2,000,000 limit.
        Customer alice = newCustomer(100_000_000);
        Customer bob = newCustomer(0);
        transfer(alice, bob, 100_000_000).andExpect(status().isAccepted());
        transfer(alice, bob, 60_000_000).andExpect(status().isAccepted());

        transfer(alice, bob, 50_000_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_BLOCKED"))
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.startsWith("Above the daily limit"))));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE from_account_id = ? AND status = 'BLOCKED'",
                Integer.class, alice.accountId())).isEqualTo(1);
        assertThat(balance(alice.accountId())).isEqualTo(100_000_000);
    }

    @Test
    void unknownRecipientAndSameAccountAreRefused() throws Exception {
        Customer alice = newCustomer(1_000_000);
        transfer(alice, "999999999999", 1_000, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DESTINATION"));
        transfer(alice, alice, 1_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
    }

    @Test
    void guessingAccountNumbersIsRateLimited() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        for (int i = 0; i < 5; i++) {
            transfer(alice, "99999999990" + i, 1_000, UUID.randomUUID().toString())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_DESTINATION"));
        }
        transfer(alice, "999999999909", 1_000, UUID.randomUUID().toString())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // Only wrong numbers are limited: paying a real account still works.
        transfer(alice, bob, 1_000).andExpect(status().isCreated());
    }

    @Test
    void fractionalOrNegativeAmountsAreRejected() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        transfer(alice, bob, -5).andExpect(status().isBadRequest());
        postJson("/api/v1/transfers", alice.token(), """
                {"fromAccountId":"%s","toAccountNumber":"%s","amountMinor":12.5,"currency":"KES","reference":""}
                """.formatted(alice.accountId(), bob.accountNumber()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customersOnlySeeTheirOwnTransfers() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        Customer eve = newCustomer(0);
        String transferId = id(transfer(alice, bob, 1_000).andExpect(status().isCreated()));

        mvc.perform(as(get("/api/v1/transfers/" + transferId), bob.token())).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/transfers/" + transferId), eve.token())).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/transfers"), eve.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void staffCannotCreateTransfers() throws Exception {
        Customer bob = newCustomer(0);
        String maker = staff("MAKER");
        postJson("/api/v1/transfers", maker, """
                {"fromAccountId":"%s","toAccountNumber":"%s","amountMinor":1,"currency":"KES","reference":""}
                """.formatted(UUID.randomUUID(), bob.accountNumber()))
                .andExpect(status().isForbidden());
    }

    @Test
    void sendingMoneyStraightBackIsHeldForReview() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);

        transfer(alice, bob, 100_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        transfer(bob, alice, 90_000).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.fraud.decision").value("REVIEW"));

        assertThat(balance(bob.accountId())).isEqualTo(100_000);
    }

    @Test
    void rejectedRequestEventsNameTheUser(CapturedOutput output) throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        String makerName = createUser("MAKER", UUID.randomUUID());

        transfer(alice, bob, -5).andExpect(status().isBadRequest());
        postJson("/api/v1/transfers", login(makerName), """
                {"fromAccountId":"%s","toAccountNumber":"%s","amountMinor":1,"currency":"KES","reference":""}
                """.formatted(UUID.randomUUID(), bob.accountNumber()))
                .andExpect(status().isForbidden());

        assertThat(securityLine(output, "transfer.invalid_amount", alice.username())).isTrue();
        assertThat(securityLine(output, "authz.access.denied", makerName)).isTrue();
    }

    private static boolean securityLine(CapturedOutput output, String action, String username) {
        return output.getOut().lines().anyMatch(l -> l.contains("\"logger_name\":\"security\"")
                && l.contains("\"action\":\"" + action + "\"")
                && l.contains("\"username\":\"" + username + "\""));
    }
}
