package com.jengabank.payments.transfer;

import com.jengabank.payments.auth.Actor;
import com.jengabank.payments.auth.ActorResolver;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService service;
    private final ActorResolver actors;

    public TransferController(TransferService service, ActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody TransferRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId,
            @RequestHeader(name = SecurityEventLogger.ROLE_HEADER, required = false) String userRole) {
        TransferResponse response = service.create(request, actors.resolve(userId, userRole), idempotencyKey);

        return switch (response.status()) {
            case "COMPLETED" -> ResponseEntity.status(HttpStatus.CREATED).body(response);
            case "PENDING_APPROVAL" -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            case "BLOCKED" -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of(
                            "code", Actions.FRAUD_BLOCK,
                            "message", "Transfer blocked by fraud rules",
                            "status", "BLOCKED",
                            "reference", response.reference()));
            default -> ResponseEntity.ok(response);
        };
    }

    @GetMapping("/{id}")
    public TransferResponse get(
            @PathVariable Long id,
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId,
            @RequestHeader(name = SecurityEventLogger.ROLE_HEADER, required = false) String userRole) {
        return service.get(id);
    }
}