package com.jengabank.payments.audit;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Writes one hash-chained audit row. Call inside the same transaction as the change it records. */
@Component
public class AuditWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void write(String actor, String action, String target, Map<String, ?> details) {
        try {
            jdbc.update("INSERT INTO audit_log (actor, action, target, details) VALUES (?, ?, ?, ?::jsonb)",
                    actor, action, target, mapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise audit details", e);
        }
    }
}
