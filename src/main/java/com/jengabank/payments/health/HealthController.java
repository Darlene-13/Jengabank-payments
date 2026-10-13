package com.jengabank.payments.health;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * /healthz  - liveness: the process is up and serving HTTP. Never touches the database,
 *             so a slow database doesn't get the container restarted.
 * /readyz   - readiness: the database answers and Flyway has applied our migrations.
 *
 * Both are public (permit them in SecurityConfig) and reveal nothing about versions or internals.
 */
@Tag(name = "Health")
@SecurityRequirements
@RestController
public class HealthController {

    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping(value = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(Map.of("status", "UP"));
    }

    @GetMapping(value = "/readyz", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> ready() {
        try {
            Integer migrated = jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
            if (migrated == null || migrated == 0) {
                return notReady();
            }
            return ResponseEntity.ok().cacheControl(NO_STORE).body(Map.of("status", "READY"));
        } catch (DataAccessException e) {
            return notReady();
        }
    }

    private ResponseEntity<Map<String, String>> notReady() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(NO_STORE)
                .body(Map.of("status", "NOT_READY"));
    }
}
