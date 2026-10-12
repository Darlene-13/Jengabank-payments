package com.jengabank.payments.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records every security-relevant decision in the append-only audit trail.
 *
 * <p>Each write runs in its own transaction ({@code REQUIRES_NEW}) so an audit
 * row survives even when the transaction it is describing rolls back - a rejected
 * transfer must still leave a trail.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;
    private final ObjectMapper mapper;

    public AuditService(AuditRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String target, Map<String, Object> details) {
        try {
            Map<String, Object> payload = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
            repository.insert(actor == null ? "system" : actor, action, target, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("audit details not serialisable for action {}", action, e);
            repository.insert(actor == null ? "system" : actor, action, target, "{}");
        }
    }

    public void record(String actor, String action, String target) {
        record(actor, action, target, Map.of());
    }

    public AuditRecord latest() {
        return repository.latest().orElse(null);
    }

    public long countByAction(String action) {
        return repository.countByAction(action);
    }
}
