package com.jengabank.payments.reversal;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.auth.CurrentUser;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RateLimiter;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.reversal.ReversalDtos.RejectRequest;
import com.jengabank.payments.reversal.ReversalDtos.ReversalDto;

import com.jengabank.payments.reversal.ReversalDtos.ReversalRequest;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Roles are enforced twice: by URL in SecurityConfig and by @PreAuthorize here. */
@Tag(name = "Reversals")
@RestController
public class ReversalController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int WINDOW_SECONDS = 60;

    private final ReversalService service;
    private final RateLimiter rateLimiter;
    private final SecurityEventLogger events;
    private final int listRatePerMinute;

    public ReversalController(ReversalService service, RateLimiter rateLimiter, SecurityEventLogger events,
                              @Value("${jengabank.security.reversals-list-rate-per-minute:30}") int listRatePerMinute) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.listRatePerMinute = listRatePerMinute;
    }

    @PostMapping("/api/v1/transfers/{transferId}/reversals")
    @PreAuthorize("hasRole('MAKER')")
    public ResponseEntity<?> request(@PathVariable UUID transferId,
                                     @RequestHeader(IDEMPOTENCY_KEY) String key,
                                     @Valid @RequestBody ReversalRequest body) {
        return service.request(CurrentUser.require(), transferId, body, key);
    }

    @GetMapping("/api/v1/reversals")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER')")
    public Page<ReversalDto> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        AuthenticatedUser me = CurrentUser.require();
        if (!rateLimiter.allow("reversals.list:" + me.id(), listRatePerMinute, WINDOW_SECONDS)) {
            events.warn(Actions.RATE_LIMITED, "username", me.username(), "endpoint", "reversals.list");
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Wait a minute, then try again.");
        }
        return service.list(status, cursor, limit);
    }

    @GetMapping("/api/v1/reversals/{reversalId}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER')")
    public ReversalDto get(@PathVariable UUID reversalId) {
        return service.get(reversalId);
    }

    @PostMapping("/api/v1/reversals/{reversalId}/approve")
    @PreAuthorize("hasRole('CHECKER')")
    public ResponseEntity<?> approve(@PathVariable UUID reversalId, @RequestHeader(IDEMPOTENCY_KEY) String key) {
        return service.approve(CurrentUser.require(), reversalId, key);
    }

    @PostMapping("/api/v1/reversals/{reversalId}/reject")
    @PreAuthorize("hasRole('CHECKER')")
    public ResponseEntity<?> reject(@PathVariable UUID reversalId,
                                    @RequestHeader(IDEMPOTENCY_KEY) String key,
                                    @Valid @RequestBody RejectRequest body) {
        return service.reject(CurrentUser.require(), reversalId, body, key);
    }
}
