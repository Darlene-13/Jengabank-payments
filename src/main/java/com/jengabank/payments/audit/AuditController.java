package com.jengabank.payments.audit;

import com.jengabank.payments.auth.Actor;
import com.jengabank.payments.auth.ActorResolver;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditRepository repository;
    private final ActorResolver actors;
    private final SecurityEventLogger events;

    public AuditController(AuditRepository repository, ActorResolver actors, SecurityEventLogger events) {
        this.repository = repository;
        this.actors = actors;
        this.events = events;
    }

    @GetMapping
    public List<AuditRecord> recent(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId,
            @RequestHeader(name = SecurityEventLogger.ROLE_HEADER, required = false) String userRole) {
        Actor actor = actors.resolve(userId, userRole);
        requireAuditRole(actor, "/api/v1/audit");
        int safeLimit = Math.min(Math.max(limit, 1), 100); // Cursor paging contract: max 100
        return repository.recent(safeLimit);
    }

    @GetMapping("/verify")
    public Map<String, Object> verify(
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId,
            @RequestHeader(name = SecurityEventLogger.ROLE_HEADER, required = false) String userRole) {
        Actor actor = actors.resolve(userId, userRole);
        requireAuditRole(actor, "/api/v1/audit/verify");

        AuditRepository.AuditVerifyResult result = repository.verifyChain();
        String action = result.valid() ? Actions.AUDIT_CHAIN_OK : Actions.AUDIT_CHAIN_BROKEN;
        events.emit(action, 200, actor.id(), Map.of(
                "path", "/api/v1/audit/verify",
                "checked", result.checked(),
                "broken_at", result.brokenAt() == null ? "null" : result.brokenAt()));
        return Map.of(
                "valid", result.valid(),
                "checked", result.checked(),
                "brokenAt", result.brokenAt() == null ? "null" : result.brokenAt());
    }

    private void requireAuditRole(Actor actor, String path) {
        if (!actor.role().canViewAudit()) {
            events.emit(Actions.AUTH_FORBIDDEN, 403, actor.id(),
                    Map.of("path", path, "reason", "requires_MAKER_or_CHECKER"));
            throw new BusinessException(HttpStatus.FORBIDDEN, Actions.AUTH_FORBIDDEN,
                    "Only MAKER or CHECKER may read the audit trail");
        }
    }
}