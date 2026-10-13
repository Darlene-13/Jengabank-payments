package com.jengabank.payments.approval;

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

import com.jengabank.payments.transfer.TransferDtos.TransferDto;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Transfer approvals only. Reversal approvals live at /api/v1/reversals/{id}/approve|reject. */
@Tag(name = "Approvals")
@RestController
@RequestMapping("/api/v1/approvals")
@PreAuthorize("hasRole('CHECKER')")
public class ApprovalController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int WINDOW_SECONDS = 60;

    private final MakerCheckerService service;
    private final RateLimiter rateLimiter;
    private final SecurityEventLogger events;
    private final int listRatePerMinute;

    public ApprovalController(MakerCheckerService service, RateLimiter rateLimiter, SecurityEventLogger events,
                              @Value("${jengabank.security.approvals-list-rate-per-minute:30}") int listRatePerMinute) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.listRatePerMinute = listRatePerMinute;
    }

    @GetMapping
    public Page<TransferDto> pending(@RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        AuthenticatedUser me = CurrentUser.require();
        if (!rateLimiter.allow("approvals.list:" + me.id(), listRatePerMinute, WINDOW_SECONDS)) {
            events.warn(Actions.RATE_LIMITED, "username", me.username(), "endpoint", "approvals.list");
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Wait a minute, then try again.");
        }
        return service.pending(me, cursor, limit);
    }

    @PostMapping("/{transferId}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID transferId, @RequestHeader(IDEMPOTENCY_KEY) String key) {
        return service.approve(CurrentUser.require(), transferId, key);
    }

    @PostMapping("/{transferId}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID transferId,
                                    @RequestHeader(IDEMPOTENCY_KEY) String key,
                                    @Valid @RequestBody RejectTransferRequest body) {
        return service.reject(CurrentUser.require(), transferId, body, key);
    }
}
