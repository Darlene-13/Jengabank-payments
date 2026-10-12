package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

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
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
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
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.reasons[0]").exists());
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
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
    }

    @Test
    void reachingTheDailyLimitIsBlockedAndKeptAsEvidence() throws Exception {
        // KES 1,000,000 twice = exactly the KES 2,000,000 daily limit: the velocity rule blocks the second.
        Customer alice = newCustomer(100_000_000);
        Customer bob = newCustomer(0);
        transfer(alice, bob, 100_000_000).andExpect(status().isAccepted());

        transfer(alice, bob, 100_000_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FRAUD_BLOCKED"))
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.startsWith("fraud.velocity"))));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE from_account_id = ? AND status = 'BLOCKED'",
                Integer.class, alice.accountId())).isEqualTo(1);
        assertThat(balance(alice.accountId())).isEqualTo(100_000_000);
    }

    @Test
    void unknownRecipientAndSameAccountAreRefused() throws Exception {
        Customer alice = newCustomer(1_000_000);
        transfer(alice, "999999999999", 1_000, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
        transfer(alice, alice, 1_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
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
}
