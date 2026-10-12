package com.jengabank.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

/**
 * One throwaway Postgres for all API tests (Docker needed). Every test makes its own
 * random users and accounts, so tests don't depend on each other or on seed data.
 * The odd-hours fraud rule is switched off so results don't depend on the clock.
 */
@SpringBootTest(properties = {
        "jengabank.auth.login-rate-per-minute=1000",
        "jengabank.fraud.odd-hour-start=0",
        "jengabank.fraud.odd-hour-end=0"
})
@AutoConfigureMockMvc
abstract class AbstractApiIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        DB.start();
    }

    static final String PASSWORD = "Test-" + UUID.randomUUID();
    static final String FUNDING = "10000000-0000-4000-8000-000000000000";

    /** KES 250,000: at or above this a transfer waits for a checker. */
    static final long THRESHOLD = 25_000_000L;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    record Customer(String username, String token, UUID accountId, String accountNumber) {}

    /** Creates a user with the shared test password and returns the username. */
    String createUser(String role, UUID userId) {
        String username = role.toLowerCase().charAt(0) + "" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        jdbc.update("INSERT INTO users (id, username, display_name, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                userId, username, "Test " + username, encoder.encode(PASSWORD), role);
        return username;
    }

    /** A signed-in MAKER or CHECKER. Returns the bearer token. */
    String staff(String role) {
        return login(createUser(role, UUID.randomUUID()));
    }

    Customer newCustomer(long openingMinor) {
        UUID userId = UUID.randomUUID();
        String username = createUser("CUSTOMER", userId);
        UUID accountId = UUID.randomUUID();
        String number = String.format("%012d", ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999_999L));
        jdbc.update("INSERT INTO accounts (id, account_number, owner_id, name, type) VALUES (?, ?, ?, 'Everyday', 'CUSTOMER')",
                accountId, number, userId);
        if (openingMinor > 0) {
            UUID journalId = UUID.randomUUID();
            jdbc.update("INSERT INTO journals (id, kind, description) VALUES (?, 'OPENING', 'Opening balance')", journalId);
            jdbc.update("INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES (?, ?::uuid, 'DEBIT', ?), (?, ?, 'CREDIT', ?)",
                    journalId, FUNDING, openingMinor, journalId, accountId, openingMinor);
        }
        return new Customer(username, login(username), accountId, number);
    }

    String login(String username) {
        try {
            String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(body, "$.token");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    ResultActions transfer(Customer from, String toNumber, long amountMinor, String key) throws Exception {
        MockHttpServletRequestBuilder request = as(post("/api/v1/transfers"), from.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId":"%s","toAccountNumber":"%s","amountMinor":%d,"currency":"KES","reference":"test"}
                        """.formatted(from.accountId(), toNumber, amountMinor));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    ResultActions transfer(Customer from, Customer to, long amountMinor) throws Exception {
        return transfer(from, to.accountNumber(), amountMinor, UUID.randomUUID().toString());
    }

    ResultActions postJson(String path, String token, String json) throws Exception {
        return mvc.perform(as(post(path), token).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(json));
    }

    long balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance_minor FROM account_balances WHERE account_id = ?", Long.class, accountId);
    }

    static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
