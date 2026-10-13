package com.jengabank.payments.reversal;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jengabank.payments.audit.AuditWriter;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.common.Cursors;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.ledger.LedgerService.Direction;
import com.jengabank.payments.ledger.LedgerService.Line;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.reversal.ReversalDtos.RejectRequest;
import com.jengabank.payments.reversal.ReversalDtos.ReversalDto;
import com.jengabank.payments.reversal.ReversalDtos.ReversalRequest;
import com.jengabank.payments.reversal.ReversalRepository.ReversalRow;
import com.jengabank.payments.reversal.ReversalRepository.TransferForReversal;

/**
 * G5 decision: reversals under the approval threshold post immediately;
 * at or above it they wait for a CHECKER who is not the requesting MAKER.
 * A reversal never edits the original: it posts a new journal with the entries swapped.
 */
@Service
public class ReversalService {

    private static final Set<String> STATUSES = Set.of("PENDING_APPROVAL", "POSTED", "REJECTED");

    private final ReversalRepository repo;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final SecurityEventLogger events;
    private final long thresholdMinor;

    public ReversalService(ReversalRepository repo, LedgerService ledger, IdempotencyService idempotency,
                           AuditWriter audit, SecurityEventLogger events,
                           @Value("${jengabank.approval.threshold-minor:25000000}") long thresholdMinor) {
        this.repo = repo;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.thresholdMinor = thresholdMinor;
    }

    // ---- MAKER requests a reversal -------------------------------------------

    @Transactional
    public ResponseEntity<?> request(AuthenticatedUser maker, UUID transferId, ReversalRequest body, String key) {
        String endpoint = "POST /api/v1/transfers/" + transferId + "/reversals";
        return idempotency.execute(maker, key, endpoint, body, () -> doRequest(maker, transferId, body.reason().trim()));
    }

    private ResponseEntity<?> doRequest(AuthenticatedUser maker, UUID transferId, String reason) {
        TransferForReversal transfer = repo.lockTransfer(transferId).orElseThrow(() -> {
            events.warn(Actions.REVERSAL_DENIED, "username", maker.username(), "transfer_id", transferId, "reason", "not_found");
            return BusinessException.notFound();
        });

        if ("REVERSED".equals(transfer.status())) {
            events.warn(Actions.REVERSAL_DUPLICATE_DENIED, "username", maker.username(), "transfer_id", transferId);
            throw BusinessException.conflict("REVERSAL_EXISTS", "This transfer has already been reversed.");
        }
        if (!"COMPLETED".equals(transfer.status())) {
            events.warn(Actions.REVERSAL_DENIED, "username", maker.username(), "transfer_id", transferId,
                    "reason", "status_" + transfer.status());
            throw BusinessException.rule("REVERSAL_NOT_ALLOWED", "Only completed transfers can be reversed.");
        }
        if (repo.hasLiveReversal(transferId)) {
            events.warn(Actions.REVERSAL_DUPLICATE_DENIED, "username", maker.username(), "transfer_id", transferId);
            throw BusinessException.conflict("REVERSAL_EXISTS", "This transfer already has a reversal.");
        }

        UUID reversalId = UUID.randomUUID();
        Map<String, Object> details = Map.of("transfer_id", transferId.toString(),
                "amount_minor", transfer.amountMinor(), "reason", reason);

        if (transfer.amountMinor() >= thresholdMinor) {
            repo.insert(reversalId, transferId, reason, "PENDING_APPROVAL", null, maker.id());
            audit.write(maker.username(), Actions.REVERSAL_PENDING_APPROVAL, "reversal/" + reversalId, details);
            events.info(Actions.REVERSAL_PENDING_APPROVAL, "username", maker.username(),
                    "reversal_id", reversalId, "transfer_id", transferId, "amount_minor", transfer.amountMinor());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(repo.find(reversalId).orElseThrow());
        }

        UUID journalId = postReversal(transfer, maker.id());
        repo.insert(reversalId, transferId, reason, "POSTED", journalId, maker.id());
        audit.write(maker.username(), Actions.REVERSAL_CREATED, "reversal/" + reversalId, details);
        events.info(Actions.REVERSAL_CREATED, "username", maker.username(), "reversal_id", reversalId,
                "transfer_id", transferId, "journal_id", journalId, "amount_minor", transfer.amountMinor());
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.find(reversalId).orElseThrow());
    }

    // ---- CHECKER approves or rejects -----------------------------------------

    @Transactional
    public ResponseEntity<?> approve(AuthenticatedUser checker, UUID reversalId, String key) {
        String endpoint = "POST /api/v1/reversals/" + reversalId + "/approve";
        return idempotency.execute(checker, key, endpoint, Map.of(), () -> {
            ReversalRow reversal = lockPending(checker, reversalId);
            TransferForReversal transfer = repo.lockTransfer(reversal.transferId()).orElseThrow(BusinessException::notFound);
            if (!"COMPLETED".equals(transfer.status())) {
                throw BusinessException.rule("REVERSAL_NOT_ALLOWED", "The original transfer is no longer completed.");
            }

            UUID journalId = postReversal(transfer, checker.id());
            if (repo.markPosted(reversalId, journalId, checker.id()) != 1) {
                throw BusinessException.conflict("INVALID_STATE", "This reversal has already been decided.");
            }
            audit.write(checker.username(), Actions.APPROVAL_APPROVED, "reversal/" + reversalId,
                    Map.of("type", "reversal", "transfer_id", transfer.id().toString(), "journal_id", journalId.toString()));
            events.info(Actions.APPROVAL_APPROVED, "username", checker.username(), "type", "reversal",
                    "reversal_id", reversalId, "journal_id", journalId);
            events.info(Actions.REVERSAL_CREATED, "username", checker.username(), "reversal_id", reversalId,
                    "transfer_id", transfer.id(), "journal_id", journalId, "amount_minor", transfer.amountMinor());
            return ResponseEntity.ok(repo.find(reversalId).orElseThrow());
        });
    }

    @Transactional
    public ResponseEntity<?> reject(AuthenticatedUser checker, UUID reversalId, RejectRequest body, String key) {
        String endpoint = "POST /api/v1/reversals/" + reversalId + "/reject";
        return idempotency.execute(checker, key, endpoint, body, () -> {
            lockPending(checker, reversalId);
            if (repo.markRejected(reversalId, checker.id()) != 1) {
                throw BusinessException.conflict("INVALID_STATE", "This reversal has already been decided.");
            }
            audit.write(checker.username(), Actions.APPROVAL_REJECTED, "reversal/" + reversalId,
                    Map.of("type", "reversal", "reason", body.reason().trim()));
            events.info(Actions.APPROVAL_REJECTED, "username", checker.username(), "type", "reversal",
                    "reversal_id", reversalId);
            return ResponseEntity.ok(repo.find(reversalId).orElseThrow());
        });
    }

    // ---- Reads -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReversalDto get(UUID reversalId) {
        return repo.find(reversalId).orElseThrow(BusinessException::notFound);
    }

    @Transactional(readOnly = true)
    public Page<ReversalDto> list(String status, String cursor, Integer limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw BusinessException.badRequest("INVALID_PARAMETER", "Unknown status filter.");
        }
        int lim = Cursors.limit(limit);
        OffsetDateTime beforeTs = null;
        UUID beforeId = null;
        String raw = Cursors.decode(cursor);
        if (raw != null) {
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw Cursors.invalid();
            }
            try {
                beforeTs = OffsetDateTime.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw Cursors.invalid();
            }
        }
        return Page.of(repo.list(status, beforeTs, beforeId, lim + 1), lim, r -> r.requestedAt() + "|" + r.id());
    }

    // ---- Helpers ---------------------------------------------------------------

    private ReversalRow lockPending(AuthenticatedUser checker, UUID reversalId) {
        ReversalRow reversal = repo.lockReversal(reversalId).orElseThrow(BusinessException::notFound);
        if (!"PENDING_APPROVAL".equals(reversal.status())) {
            events.warn(Actions.REVERSAL_DENIED, "username", checker.username(), "reversal_id", reversalId,
                    "reason", "invalid_state_" + reversal.status());
            throw BusinessException.conflict("INVALID_STATE", "This reversal has already been decided.");
        }
        if (reversal.requestedBy().equals(checker.id())) {
            events.warn(Actions.APPROVAL_SELF_APPROVAL_DENIED, "username", checker.username(), "type", "reversal",
                    "reversal_id", reversalId);
            throw new BusinessException(HttpStatus.FORBIDDEN, "SELF_APPROVAL",
                    "You requested this reversal, so another approver must decide.");
        }
        return reversal;
    }

    /** Posts the swapped entries. The original journal and entries are never touched. */
    private UUID postReversal(TransferForReversal transfer, UUID actorId) {
        ledger.lockAccounts(transfer.fromAccountId(), transfer.toAccountId());
        if (ledger.balance(transfer.toAccountId()) < transfer.amountMinor()) {
            throw BusinessException.rule("REVERSAL_INSUFFICIENT_FUNDS",
                    "The receiving account no longer holds enough money to reverse this transfer.");
        }
        String reference = transfer.reference() == null || transfer.reference().isBlank() ? "transfer" : transfer.reference();
        UUID journalId = ledger.post("REVERSAL", "Reversal of " + reference, transfer.journalId(), actorId, List.of(
                new Line(transfer.toAccountId(), Direction.DEBIT, transfer.amountMinor()),
                new Line(transfer.fromAccountId(), Direction.CREDIT, transfer.amountMinor())));
        if (repo.markTransferReversed(transfer.id(), journalId) != 1) {
            throw BusinessException.conflict("INVALID_STATE", "This transfer was changed by another request.");
        }
        return journalId;
    }
}
