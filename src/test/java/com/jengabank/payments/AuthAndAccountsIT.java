package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.jengabank.payments.logging.RateLimitCleanupJob;

@SpringBootTest(properties = {
        "jengabank.auth.login-rate-per-minute=1000",
        "jengabank.security.account-entries-rate-per-minute=3"
})
@AutoConfigureMockMvc
@Testcontainers
class AuthAndAccountsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Test-" + UUID.randomUUID();
    private static final String FUNDING = "10000000-0000-4000-8000-000000000000";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RateLimitCleanupJob cleanupJob;

    // ---- helpers ------------------------------------------------------------

    private String newCustomer() {
        return "t" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }

    private UUID createCustomer(String username, long openingMinor) {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        String number = String.format("%012d", ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999_999L));
        jdbc.update("INSERT INTO users (id, username, display_name, password_hash, role) VALUES (?, ?, ?, ?, 'CUSTOMER')",
                userId, username, "Test " + username, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO accounts (id, account_number, owner_id, name, type) VALUES (?, ?, ?, 'Everyday', 'CUSTOMER')",
                accountId, number, userId);
        jdbc.update("INSERT INTO journals (id, kind, description) VALUES (?, 'OPENING', 'Opening balance')", journalId);
        jdbc.update("INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES (?, ?::uuid, 'DEBIT', ?), (?, ?, 'CREDIT', ?)",
                journalId, FUNDING, openingMinor, journalId, accountId, openingMinor);
        return accountId;
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    // ---- tests --------------------------------------------------------------

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/readyz")).andExpect(status().isOk());
    }

    @Test
    void apiWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void garbageTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerSeesOwnAccountWithDerivedBalance() throws Exception {
        String user = newCustomer();
        UUID account = createCustomer(user, 1_234_567);
        String token = login(user);

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(account.toString()))
                .andExpect(jsonPath("$.items[0].balanceMinor").value(1_234_567));

        mvc.perform(get("/api/v1/accounts/" + account + "/entries").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].amountMinor").value(1_234_567));
    }

    @Test
    void cannotSeeSomeoneElsesAccount() throws Exception {
        String alice = newCustomer();
        String bob = newCustomer();
        createCustomer(alice, 100);
        UUID bobsAccount = createCustomer(bob, 100);
        String aliceToken = login(alice);

        mvc.perform(get("/api/v1/accounts/" + bobsAccount).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/" + bobsAccount + "/entries").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void lockedAfterFiveBadPasswordsEvenWithTheRightOneAfter() throws Exception {
        String user = newCustomer();
        createCustomer(user, 100);
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(user, "wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(user, "wrong")))
                .andExpect(status().isLocked());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(user, PASSWORD)))
                .andExpect(status().isLocked());
    }

    @Test
    void unknownUserLooksExactlyLikeWrongPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("nobody.here", "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void logoutRevokesTheToken() throws Exception {
        String user = newCustomer();
        createCustomer(user, 100);
        String token = login(user);

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cannotSmuggleARoleIntoLogin() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"y\",\"role\":\"CHECKER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void customerCannotUseStaffEndpoints() throws Exception {
        String user = newCustomer();
        createCustomer(user, 100);
        String token = login(user);
        mvc.perform(get("/api/v1/approvals").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void bodyOver16kIsRejectedBeforeItsEvenParsed() throws Exception {
        String oversized = loginBody("x", "y".repeat(20_000));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void spoofedForwardedForDoesNotChangeTheIpUsedForRateLimiting() throws Exception {
        // No jengabank.security.trusted-proxies configured here, so this header must be ignored:
        // the row the rate limiter writes should key on MockMvc's real test client address, not
        // the one an attacker claims in the header.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.1")
                        .content(loginBody("nobody.here", "x")));

        String ip = jdbc.queryForObject(
                "SELECT ip_address FROM login_rate_limits ORDER BY window_start DESC LIMIT 1", String.class);
        assertThat(ip).isNotEqualTo("203.0.113.1");
    }

    @Test
    void entriesAreRateLimitedPerUser() throws Exception {
        // jengabank.security.account-entries-rate-per-minute=3 for this test class.
        String user = newCustomer();
        UUID account = createCustomer(user, 100);
        String token = login(user);

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/accounts/" + account + "/entries").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/accounts/" + account + "/entries").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // A different user's own calls aren't affected by the first user's limit.
        String other = newCustomer();
        UUID otherAccount = createCustomer(other, 100);
        mvc.perform(get("/api/v1/accounts/" + otherAccount + "/entries").header("Authorization", "Bearer " + login(other)))
                .andExpect(status().isOk());
    }

    @Test
    void cleanupJobPrunesStaleWindowsButKeepsFreshOnes() {
        // Other tests in this class also write to these tables; start from a clean slate
        // so this test doesn't depend on execution order.
        jdbc.update("DELETE FROM login_rate_limits");
        jdbc.update("DELETE FROM rate_limits");

        jdbc.update("INSERT INTO login_rate_limits (ip_address, window_start, attempt_count) VALUES (?, now() - INTERVAL '20 minutes', 5)",
                "203.0.113.55");
        jdbc.update("INSERT INTO login_rate_limits (ip_address, window_start, attempt_count) VALUES (?, now(), 2)",
                "203.0.113.66");
        jdbc.update("INSERT INTO rate_limits (bucket_key, window_start, attempt_count) VALUES (?, now() - INTERVAL '20 minutes', 5)",
                "test.stale:1");
        jdbc.update("INSERT INTO rate_limits (bucket_key, window_start, attempt_count) VALUES (?, now(), 2)",
                "test.fresh:1");

        cleanupJob.pruneStaleWindows();

        assertThat(jdbc.queryForList("SELECT ip_address FROM login_rate_limits", String.class))
                .containsExactly("203.0.113.66");
        assertThat(jdbc.queryForList("SELECT bucket_key FROM rate_limits", String.class))
                .containsExactly("test.fresh:1");
    }
}
