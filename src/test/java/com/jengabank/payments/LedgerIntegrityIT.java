package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** The database's own guards (V2, V3), reconciliation, the audit chain and concurrent spending. */
class LedgerIntegrityIT extends AbstractApiIT {

    @Autowired TransactionTemplate tx;

    @Test
    void anUnbalancedJournalCannotCommit() {
        Customer alice = newCustomer(0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID journal = jdbc.queryForObject(
                    "INSERT INTO journals (kind, description) VALUES ('TRANSFER', 'bad') RETURNING id", UUID.class);
            jdbc.update("INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES (?, ?, 'CREDIT', 100)",
                    journal, alice.accountId());
        })).isInstanceOf(DataAccessException.class);
        assertThat(balance(alice.accountId())).isZero();
    }

    @Test
    void aCustomerAccountCannotGoNegative() {
        Customer alice = newCustomer(100);
        Customer bob = newCustomer(0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID journal = jdbc.queryForObject(
                    "INSERT INTO journals (kind, description) VALUES ('TRANSFER', 'overdraft') RETURNING id", UUID.class);
            jdbc.update("INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES (?, ?, 'DEBIT', 101), (?, ?, 'CREDIT', 101)",
                    journal, alice.accountId(), journal, bob.accountId());
        })).isInstanceOf(DataAccessException.class);
        assertThat(balance(alice.accountId())).isEqualTo(100);
    }

    @Test
    void moneyRecordsAndTheAuditLogAreAppendOnly() {
        Customer alice = newCustomer(100);
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1 WHERE account_id = ?", alice.accountId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journals")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void parallelSpendingCannotOverdraw() throws Exception {
        Customer alice = newCustomer(100_000);
        Customer bob = newCustomer(0);
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(() -> transfer(alice, bob, 60_000).andReturn().getResponse().getStatus());
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> f : pool.invokeAll(attempts)) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdown();
        }
        assertThat(statuses).containsOnlyOnce(201);
        assertThat(balance(alice.accountId())).isEqualTo(40_000);
        assertThat(balance(bob.accountId())).isEqualTo(60_000);
    }

    @Test
    void reconciliationPassesAfterRealActivity() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        String transferId = id(transfer(alice, bob, 5_000).andExpect(status().isCreated()));
        postJson("/api/v1/transfers/" + transferId + "/reversals", staff("MAKER"), "{\"reason\":\"Reconciliation test case\"}")
                .andExpect(status().isCreated());

        String checker = staff("CHECKER");
        postJson("/api/v1/reconciliation/runs", checker, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.checks[?(@.passed == false)]").isEmpty());
        mvc.perform(as(get("/api/v1/reconciliation/runs/latest"), staff("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
    }

    @Test
    void auditChainVerifiesAndPagesNewestFirst() throws Exception {
        Customer alice = newCustomer(1_000_000);
        Customer bob = newCustomer(0);
        transfer(alice, bob, 1_000).andExpect(status().isCreated());

        String maker = staff("MAKER");
        mvc.perform(as(get("/api/v1/audit/verify"), maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.brokenAtSequence").doesNotExist());
        mvc.perform(as(get("/api/v1/audit?limit=2"), maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }
}
