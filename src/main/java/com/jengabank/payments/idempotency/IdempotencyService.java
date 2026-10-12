package com.jengabank.payments.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.idempotency.IdempotencyRepository.Row;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;

/**
 * Idempotency-Key for every money-changing POST. Keys are per user.
 *   same key + same request      -> the stored response is replayed, nothing runs twice
 *   same key + different request -> 409
 *   no key                       -> 400
 * Runs inside the caller's transaction: if the request fails, the key is released with it.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper mapper;
    private final SecurityEventLogger events;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper mapper, SecurityEventLogger events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    /**
     * Result of {@link #begin}: either a stored response to return as-is ({@code replay} set),
     * or a freshly claimed key to pass to {@link #complete} once the work is done.
     */
    public record Start(UUID userId, UUID key, ResponseEntity<Object> replay) {
        public boolean isReplay() {
            return replay != null;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Start begin(AuthenticatedUser me, String rawKey, String method, String path, Object body) {
        UUID key = parseKey(me, rawKey, path);
        String hash = sha256(method + " " + path + " " + json(body));

        if (repository.claim(me.id(), key, path, hash)) {
            return new Start(me.id(), key, null);
        }

        Row existing = repository.find(me.id(), key).orElseThrow();
        if (!existing.requestHash().equals(hash) || existing.statusCode() == null) {
            events.warn(Actions.IDEMPOTENCY_CONFLICT, "username", me.username(), "path", path, "key", key);
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
                    "This Idempotency-Key was already used for a different request.");
        }
        events.info(Actions.IDEMPOTENCY_REPLAYED, "username", me.username(), "path", path, "key", key);
        return new Start(me.id(), key,
                ResponseEntity.status(existing.statusCode()).body(readTree(existing.responseBody())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ResponseEntity<Object> complete(Start start, HttpStatus status, Object body) {
        repository.complete(start.userId(), start.key(), status.value(), json(body));
        return ResponseEntity.status(status).body(body);
    }

    private UUID parseKey(AuthenticatedUser me, String rawKey, String path) {
        if (rawKey == null || rawKey.isBlank()) {
            events.warn(Actions.REQUEST_INVALID, "username", me.username(), "path", path,
                    "reason", "missing_idempotency_key");
            throw BusinessException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (a UUID) with this request.");
        }
        try {
            return UUID.fromString(rawKey.trim());
        } catch (IllegalArgumentException e) {
            events.warn(Actions.REQUEST_INVALID, "username", me.username(), "path", path,
                    "reason", "malformed_idempotency_key");
            throw BusinessException.badRequest("IDEMPOTENCY_KEY_INVALID", "The Idempotency-Key must be a UUID.");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("response is not serialisable", e);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored response is not readable", e);
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
