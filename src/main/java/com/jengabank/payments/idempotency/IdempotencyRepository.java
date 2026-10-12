package com.jengabank.payments.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("key"),
            rs.getString("request_hash"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> find(String key) {
        List<IdempotencyRecord> rows = jdbc.query(
                "SELECT \"key\", request_hash, response_status, response_body, created_at "
                        + "FROM idempotency_keys WHERE \"key\" = ?",
                MAPPER, key);
        return rows.stream().findFirst();
    }

    /** @return true when this key was stored by this call (false = lost race). */
    public boolean insertIfAbsent(IdempotencyRecord record) {
        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys (\"key\", request_hash, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"key\") DO NOTHING",
                record.key(),
                record.requestHash(),
                record.responseStatus(),
                record.responseBody(),
                Timestamp.from(record.createdAt() == null ? Instant.now() : record.createdAt()));
        return inserted == 1;
    }
}
