package com.jengabank.payments.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    /** A stored key. statusCode/responseBody are null until the request finishes. */
    public record Row(String requestHash, Integer statusCode, String responseBody) {}

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims (user, key) for this request. A concurrent request with the same key
     * blocks on the primary key until we commit or roll back.
     *
     * @return true if this call claimed the key
     */
    public boolean claim(UUID userId, UUID key, String endpoint, String requestHash) {
        return jdbc.update("""
                INSERT INTO idempotency_keys (user_id, idem_key, endpoint, request_hash)
                VALUES (?, ?, ?, ?) ON CONFLICT (user_id, idem_key) DO NOTHING
                """, userId, key, endpoint, requestHash) == 1;
    }

    public Optional<Row> find(UUID userId, UUID key) {
        return jdbc.query("""
                SELECT request_hash, status_code, response_body::text AS response_body
                  FROM idempotency_keys WHERE user_id = ? AND idem_key = ?
                """,
                (rs, n) -> new Row(rs.getString("request_hash"),
                        (Integer) rs.getObject("status_code"),
                        rs.getString("response_body")),
                userId, key).stream().findFirst();
    }

    public void complete(UUID userId, UUID key, int statusCode, String responseJson) {
        jdbc.update("UPDATE idempotency_keys SET status_code = ?, response_body = ?::jsonb WHERE user_id = ? AND idem_key = ?",
                statusCode, responseJson, userId, key);
    }
}
