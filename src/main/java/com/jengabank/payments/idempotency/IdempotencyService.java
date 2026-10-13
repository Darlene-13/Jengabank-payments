package com.jengabank.payments.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;

/**
 * Wraps a money-changing action so it runs at most once per (user, key).
 *  - New key: run the action, store status and body.
 *  - Same key, same request: replay the stored response; nothing runs again.
 *  - Same key, different request: 409.
 * Must run inside the caller's transaction: if the action fails, the key is rolled back too.
 * Concurrent requests with the same key queue on the unique index and then replay.
 */
@Service
public class IdempotencyService {

    private record Stored(String endpoint, String requestHash, Integer statusCode, String body) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecurityEventLogger events;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper, SecurityEventLogger events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ResponseEntity<?> execute(AuthenticatedUser user, String rawKey, String endpoint, Object request,
                                     Supplier<ResponseEntity<?>> action) {
        UUID key = parseKey(rawKey);
        String hash = sha256(endpoint + "\n" + json(request));

        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (user_id, idem_key, endpoint, request_hash)
                VALUES (?, ?, ?, ?) ON CONFLICT (user_id, idem_key) DO NOTHING""",
                user.id(), key, endpoint, hash);

        if (inserted == 1) {
            ResponseEntity<?> response = action.get();
            jdbc.update("UPDATE idempotency_keys SET status_code = ?, response_body = ?::jsonb WHERE user_id = ? AND idem_key = ?",
                    response.getStatusCode().value(), json(response.getBody()), user.id(), key);
            return response;
        }

        Stored stored = jdbc.queryForObject("""
                SELECT endpoint, request_hash, status_code, response_body::text AS body
                  FROM idempotency_keys WHERE user_id = ? AND idem_key = ?""",
                (rs, n) -> new Stored(rs.getString("endpoint"), rs.getString("request_hash"),
                        (Integer) rs.getObject("status_code"), rs.getString("body")),
                user.id(), key);

        if (stored == null || !stored.endpoint().equals(endpoint) || !stored.requestHash().equals(hash)) {
            events.warn(Actions.IDEMPOTENCY_CONFLICT, "username", user.username(), "endpoint", endpoint, "key", key);
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
                    "This Idempotency-Key was already used for a different request.");
        }
        if (stored.statusCode() == null) {
            throw BusinessException.conflict("IDEMPOTENCY_IN_PROGRESS", "This request is still being processed. Try again in a moment.");
        }

        events.info(Actions.IDEMPOTENCY_REPLAYED, "username", user.username(), "endpoint", endpoint, "key", key);
        try {
            return ResponseEntity.status(stored.statusCode()).body(mapper.readTree(stored.body()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response is not valid JSON", e);
        }
    }

    private static UUID parseKey(String raw) {
        try {
            if (raw == null || raw.length() != 36) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be a UUID.");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
