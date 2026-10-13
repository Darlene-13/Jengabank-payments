package com.jengabank.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jengabank.auth.login-rate-per-minute=1000",
        "jengabank.limits.velocity-max-transfers=1000",
        "jengabank.limits.daily-total-minor=100000000000"})
@Testcontainers
class TransferConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Test-" + UUID.randomUUID();
    private static final String FUNDING = "10000000-0000-4000-8000-000000000000";

    record Customer(String username, UUID accountId, String accountNumber) {}

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void twentyParallelTransfersNeverOverspend() throws Exception {
        Customer alice = customer(9_600_000);   // KES 96,000
        Customer bob = customer(0);
        String token = login(alice.username());

        int parallel = 20;                       // 20 x KES 10,000: only 9 can fit
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return transfer(token, alice.accountId(), bob.accountNumber(), 1_000_000, UUID.randomUUID())
                        .getStatusCode().value();
            }));
        }
        go.countDown();

        int completed = 0;
        int rejected = 0;
        for (Future<Integer> f : results) {
            int status = f.get(60, TimeUnit.SECONDS);
            if (status == 201) completed++;
            if (status == 422) rejected++;
        }
        pool.shutdown();

        assertEquals(9, completed);
        assertEquals(11, rejected);
        assertEquals(600_000L, balance(alice.accountId()));
        assertEquals(9_000_000L, balance(bob.accountId()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM ledger_entries",
                Long.class), "the whole ledger must net to zero");
    }

    @Test
    void sameIdempotencyKeyPostsExactlyOnce() {
        Customer alice = customer(5_000_000);
        Customer bob = customer(0);
        String token = login(alice.username());
        UUID key = UUID.randomUUID();

        ResponseEntity<String> first = transfer(token, alice.accountId(), bob.accountNumber(), 500_000, key);
        ResponseEntity<String> replay = transfer(token, alice.accountId(), bob.accountNumber(), 500_000, key);
        ResponseEntity<String> tampered = transfer(token, alice.accountId(), bob.accountNumber(), 600_000, key);

        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, replay.getStatusCode().value());
        assertEquals((String) JsonPath.read(first.getBody(), "$.id"), JsonPath.read(replay.getBody(), "$.id"));
        assertEquals(409, tampered.getStatusCode().value());
        assertEquals(4_500_000L, balance(alice.accountId()));
    }

    @Test
    void cannotSendFromSomeoneElsesAccount() {
        Customer alice = customer(1_000_000);
        Customer mallory = customer(0);
        String token = login(mallory.username());
        ResponseEntity<String> r = transfer(token, alice.accountId(), mallory.accountNumber(), 100, UUID.randomUUID());
        assertEquals(404, r.getStatusCode().value());
        assertEquals(1_000_000L, balance(alice.accountId()));
    }

    // ---- helpers ------------------------------------------------------------

    private Customer customer(long openingMinor) {
        String username = "c" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String number = String.format("%012d", ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999_999L));
        jdbc.update("INSERT INTO users (id, username, display_name, password_hash, role) VALUES (?, ?, ?, ?, 'CUSTOMER')",
                userId, username, "Test " + username, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO accounts (id, account_number, owner_id, name, type) VALUES (?, ?, ?, 'Everyday', 'CUSTOMER')",
                accountId, number, userId);
        if (openingMinor > 0) {
            UUID journalId = UUID.randomUUID();
            jdbc.update("INSERT INTO journals (id, kind, description) VALUES (?, 'OPENING', 'Opening balance')", journalId);
            jdbc.update("INSERT INTO ledger_entries (journal_id, account_id, direction, amount_minor) VALUES (?, ?::uuid, 'DEBIT', ?), (?, ?, 'CREDIT', ?)",
                    journalId, FUNDING, openingMinor, journalId, accountId, openingMinor);
        }
        return new Customer(username, accountId, number);
    }

    private String login(String username) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        ResponseEntity<String> r = http.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, h), String.class);
        return JsonPath.read(r.getBody(), "$.token");
    }

    private ResponseEntity<String> transfer(String token, UUID from, String to, long amountMinor, UUID key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key.toString());
        String body = String.format(
                "{\"fromAccountId\":\"%s\",\"toAccountNumber\":\"%s\",\"amountMinor\":%d,\"currency\":\"KES\",\"reference\":\"test\"}",
                from, to, amountMinor);
        return http.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private long balance(UUID accountId) {
        Long b = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM ledger_entries WHERE account_id = ?",
                Long.class, accountId);
        return b == null ? 0 : b;
    }
}
