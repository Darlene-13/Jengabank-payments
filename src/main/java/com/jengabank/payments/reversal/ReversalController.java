package com.jengabank.payments.reversal;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.auth.CurrentUser;
import com.jengabank.payments.reversal.ReversalDtos.ReversalRequest;

/** MAKER only (SecurityConfig). 201 posted, 202 waiting for a checker. */
@RestController
@RequestMapping("/api/v1/transfers")
public class ReversalController {

    private final ReversalService service;

    public ReversalController(ReversalService service) {
        this.service = service;
    }

    @PostMapping("/{id}/reversals")
    public ResponseEntity<Object> reverse(@PathVariable UUID id,
                                          @Valid @RequestBody ReversalRequest body,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return service.request(CurrentUser.require(), id, body.reason(), key);
    }
}
