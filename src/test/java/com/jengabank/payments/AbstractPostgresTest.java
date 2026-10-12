package com.jengabank.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.account.AccountRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractPostgresTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jengabank")
            .withUsername("jenga")
            .withPassword("jenga");

    static {
        POSTGRES.start();
    }

    @BeforeAll
    static void ensureContainer() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected JdbcTemplate jdbc;

    protected String newAccount(String prefix, long initialBalanceMinor) throws Exception {
        String accountNo = prefix + "-" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        AccountRequest request = new AccountRequest(accountNo, prefix, prefix + " owner", initialBalanceMinor);
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "admin")
                        .header("X-User-Role", "ADMIN")
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        return accountNo;
    }

    /**
     * 5-arg version auto-generates a fresh Idempotency-Key so existing tests keep
     * working after idempotency became required on money-changing POSTs.
     */
    protected MockHttpServletRequestBuilder transferRequest(String source, String target, long amount,
                                                            String userId, String role)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId)
                .header("X-User-Role", role)
                .header("Idempotency-Key", "auto-" + UUID.randomUUID())
                .content(mapper.writeValueAsString(
                        new com.jengabank.payments.transfer.TransferRequest(source, target, amount, "KES")));
    }

    protected MockHttpServletRequestBuilder transferRequest(String source, String target, long amount,
                                                            String userId, String role, String idempotencyKey)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        MockHttpServletRequestBuilder builder = post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId)
                .header("X-User-Role", role)
                .content(mapper.writeValueAsString(
                        new com.jengabank.payments.transfer.TransferRequest(source, target, amount, "KES")));
        if (idempotencyKey != null) {
            builder = builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder;
    }

    protected MvcResult createTransfer(String source, String target, long amount,
                                       String userId, String role) throws Exception {
        return mockMvc.perform(transferRequest(source, target, amount, userId, role)).andReturn();
    }

    protected String captureStdout(StdoutAction action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    protected long balanceOf(String accountNo) {
        Long balance = jdbc.query(
                "SELECT balance_minor FROM account_balances WHERE account_no = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                accountNo);
        return balance == null ? 0L : balance;
    }

    protected long ledgerNet() {
        Long net = jdbc.query("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries",
                rs -> rs.next() ? rs.getLong(1) : null);
        return net == null ? 0L : net;
    }

    protected long transferEntryCount(Long transferId) {
        Long count = jdbc.query("SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                transferId);
        return count == null ? 0L : count;
    }

    protected String statusOf(Long transferId) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
    }

    @FunctionalInterface
    interface StdoutAccess {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface StdoutAction {
        void run() throws Exception;
    }
}