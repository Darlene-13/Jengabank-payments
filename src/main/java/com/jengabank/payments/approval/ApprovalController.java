package com.jengabank.payments.approval;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
import com.jengabank.payments.reversal.ReversalDtos.ReversalDto;
import com.jengabank.payments.reversal.ReversalService;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;

/** CHECKER only (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    /** Body of a reject. The reason is stored on the transfer and in the audit trail. */
    public record RejectRequest(@NotBlank @Size(max = 280) String reason) {}

    /** Approve has no fields; this keeps fail-on-unknown-properties meaningful for it too. */
    public record ApproveRequest() {}

    private final MakerCheckerService transfers;
    private final ReversalService reversals;

    public ApprovalController(MakerCheckerService transfers, ReversalService reversals) {
        this.transfers = transfers;
        this.reversals = reversals;
    }

    @GetMapping
    public Page<TransferDto> pending(@RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        return transfers.pending(cursor, limit);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Object> approve(@PathVariable UUID id,
                                          @RequestBody(required = false) ApproveRequest body,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return transfers.approve(CurrentUser.require(), id, key);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Object> reject(@PathVariable UUID id,
                                         @Valid @RequestBody RejectRequest body,
                                         @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return transfers.reject(CurrentUser.require(), id, body.reason(), key);
    }

    // Large reversals requested by a MAKER wait here for a CHECKER.

    @GetMapping("/reversals")
    public Page<ReversalDto> pendingReversals(@RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) Integer limit) {
        return reversals.pending(cursor, limit);
    }

    @PostMapping("/reversals/{id}/approve")
    public ResponseEntity<Object> approveReversal(@PathVariable UUID id,
                                                  @RequestBody(required = false) ApproveRequest body,
                                                  @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return reversals.approve(CurrentUser.require(), id, key);
    }

    @PostMapping("/reversals/{id}/reject")
    public ResponseEntity<Object> rejectReversal(@PathVariable UUID id,
                                                 @Valid @RequestBody RejectRequest body,
                                                 @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return reversals.reject(CurrentUser.require(), id, body.reason(), key);
    }
}
