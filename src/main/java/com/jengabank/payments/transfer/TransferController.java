package com.jengabank.payments.transfer;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.auth.CurrentUser;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RateLimiter;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.transfer.TransferDtos.CreateTransferRequest;

import com.jengabank.payments.transfer.TransferDtos.TransferDto;

import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Transfers")
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private static final int WINDOW_SECONDS = 60;

    private final TransferService service;
    private final RateLimiter rateLimiter;
    private final SecurityEventLogger events;
    private final int listRatePerMinute;

    public TransferController(TransferService service, RateLimiter rateLimiter, SecurityEventLogger events,
                              @Value("${jengabank.security.transfers-list-rate-per-minute:30}") int listRatePerMinute) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.listRatePerMinute = listRatePerMinute;
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreateTransferRequest body) {
        return service.create(CurrentUser.require(), body, idempotencyKey);
    }

    @GetMapping
    public Page<TransferDto> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        AuthenticatedUser me = CurrentUser.require();
        if (!rateLimiter.allow("transfers.list:" + me.id(), listRatePerMinute, WINDOW_SECONDS)) {
            events.warn(Actions.RATE_LIMITED, "username", me.username(), "endpoint", "transfers.list");
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Wait a minute, then try again.");
        }
        return service.list(me, status, cursor, limit);
    }

    @GetMapping("/{transferId}")
    public TransferDto get(@PathVariable UUID transferId) {
        return service.get(CurrentUser.require(), transferId);
    }
}
