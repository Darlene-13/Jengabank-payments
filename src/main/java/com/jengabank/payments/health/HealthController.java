package com.jengabank.payments.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness/readiness endpoint used by the judges and the Docker healthcheck. */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "jengabank-payments");
        boolean dbUp;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbUp = true;
        } catch (Exception e) {
            dbUp = false;
        }
        body.put("db", dbUp ? "UP" : "DOWN");
        body.put("status", dbUp ? "UP" : "DEGRADED");
        return body;
    }
}
