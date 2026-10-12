package com.jengabank.payments.approval;

import com.jengabank.payments.auth.Actor;
import com.jengabank.payments.auth.ActorResolver;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.transfer.TransferResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final MakerCheckerService service;
    private final ActorResolver actors;

    public ApprovalController(MakerCheckerService service, ActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public List<TransferResponse> pending() {
        return service.pending().stream().map(TransferResponse::from).toList();
    }

    @PostMapping("/{id}/approve")
    public ApprovalResponse approve(
            @PathVariable Long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId,
            @RequestHeader(name = SecurityEventLogger.ROLE_HEADER, required = false) String userRole) {
        return service.approve(id, actors.resolve(userId, userRole), idempotencyKey);
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponse reject(
            @PathVariable Long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId,
            @RequestHeader(name = SecurityEventLogger.ROLE_HEADER, required = false) String userRole) {
        return service.reject(id, actors.resolve(userId, userRole), idempotencyKey);
    }
}