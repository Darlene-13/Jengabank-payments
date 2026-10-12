package com.jengabank.payments.audit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.audit.AuditDtos.AuditRecordDto;
import com.jengabank.payments.audit.AuditDtos.AuditVerification;
import com.jengabank.payments.audit.AuditRepository.ChainCheck;
import com.jengabank.payments.common.Cursors;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;

@Service
public class AuditService {

    private final AuditRepository repository;
    private final ObjectMapper mapper;
    private final SecurityEventLogger events;

    public AuditService(AuditRepository repository, ObjectMapper mapper, SecurityEventLogger events) {
        this.repository = repository;
        this.mapper = mapper;
        this.events = events;
    }

    /** Written in the caller's transaction: the record exists only if the change it describes commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String target, Map<String, Object> details) {
        repository.insert(actor, action, target, json(details));
    }

    /**
     * Written in its own transaction. Only {@link RefusalAuditAdvice} calls this, after the
     * refused request's transaction has ended; services throw {@link RefusalException} instead.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefusal(String actor, String action, String target, Map<String, Object> details) {
        repository.insert(actor, action, target, json(details));
    }

    public Page<AuditRecordDto> page(String cursor, Integer limit) {
        int lim = Cursors.limit(limit);
        String raw = Cursors.decode(cursor);
        Long before = null;
        if (raw != null) {
            try {
                before = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw Cursors.invalid();
            }
        }
        return Page.of(repository.page(before, lim + 1), lim, r -> Long.toString(r.sequence()));
    }

    public AuditVerification verify(String username) {
        ChainCheck check = repository.verify();
        if (check.valid()) {
            events.info(Actions.AUDIT_CHAIN_OK, "username", username, "checked", check.checked());
        } else {
            events.error(Actions.AUDIT_CHAIN_BROKEN, "username", username, "checked", check.checked(),
                    "broken_at", check.brokenAt());
        }
        return new AuditVerification(check.valid(), check.checked(), check.brokenAt(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String json(Map<String, Object> details) {
        try {
            return mapper.writeValueAsString(details == null ? Map.of() : new LinkedHashMap<>(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit details are not serialisable", e);
        }
    }
}
