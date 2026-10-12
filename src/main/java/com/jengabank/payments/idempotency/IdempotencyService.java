package com.jengabank.payments.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Idempotency-Key support: the same key with the same payload returns the cached
 * response; the same key with a different payload is a conflict (replay attack or
 * client bug) and is rejected.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public Optional<IdempotencyRecord> find(String key) {
        if (isBlank(key)) {
            return Optional.empty();
        }
        return repository.find(key.trim());
    }

    /** Stable hash of a request payload. */
    public String hash(Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            json = String.valueOf(payload);
        }
        return sha256(json);
    }

    public String hash(String payload) {
        return sha256(payload == null ? "" : payload);
    }

    public void remember(String key, String requestHash, int status, String body) {
        if (isBlank(key)) {
            return;
        }
        repository.insertIfAbsent(new IdempotencyRecord(key.trim(), requestHash, status, body, Instant.now()));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
