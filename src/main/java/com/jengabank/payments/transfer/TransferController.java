package com.jengabank.payments.transfer;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.auth.CurrentUser;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.transfer.TransferDtos.CreateTransferRequest;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;

/**
 * POST: CUSTOMER only (SecurityConfig). 201 completed, 202 waiting for a checker,
 * 422 FRAUD_BLOCKED. GET: customers see their own transfers, staff see all.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Object> create(@Valid @RequestBody CreateTransferRequest body,
                                         @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return service.create(CurrentUser.require(), body, key);
    }

    @GetMapping
    public Page<TransferDto> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        return service.list(CurrentUser.require(), status, cursor, limit);
    }

    @GetMapping("/{id}")
    public TransferDto get(@PathVariable UUID id) {
        return service.get(CurrentUser.require(), id);
    }
}
