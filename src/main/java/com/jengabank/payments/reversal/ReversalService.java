package com.jengabank.payments.reversal;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jengabank.payments.audit.AuditService;
import com.jengabank.payments.audit.RefusalException;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.common.Cursors;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.config.JengaProperties;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.ledger.LedgerRepository;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.reversal.ReversalDtos.ReversalDto;
import com.jengabank.payments.transfer.Transfer;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;
import com.jengabank.payments.transfer.TransferRepository;
import com.jengabank.payments.transfer.TransferStatus;

/**
 * A reversal never edits the original: it posts a new journal with the entries
 * swapped. Each transfer can be reversed once (UNIQUE reversal_of in V1).
 * Reversals under the approval threshold post at once; larger ones wait for a
 * CHECKER who isn't the MAKER who asked.
 */
@Service
public class ReversalService {

    private final ReversalRepository reversals;
    private final TransferRepository transfers;
    private final LedgerRepository accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final SecurityEventLogger events;
    private final long thresholdMinor;

    public ReversalService(ReversalRepository reversals, TransferRepository transfers, LedgerRepository accounts,
                           LedgerService ledger, IdempotencyService idempotency, AuditService audit,
                           SecurityEventLogger events, JengaProperties properties) {
        this.reversals = reversals;
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.thresholdMinor = properties.approval().thresholdMinor();
    }

    @Transactional
    public ResponseEntity<Object> request(AuthenticatedUser maker, UUID transferId, String reason, String idempotencyKey) {
        String path = "/api/v1/transfers/" + transferId + "/reversals";
        IdempotencyService.Start idem = idempotency.begin(maker, idempotencyKey, "POST", path, Map.of("reason", reason));
        if (idem.isReplay()) {
            return idem.replay();
        }
        Transfer t = transfers.findForUpdate(transferId).orElseThrow(BusinessException::notFound);
        if (t.status() == TransferStatus.REVERSED || reversals.hasLive(transferId)) {
            events.warn(Actions.REVERSAL_DUPLICATE_DENIED, "username", maker.username(), "transfer_id", transferId);
            throw new RefusalException(HttpStatus.CONFLICT, "ALREADY_REVERSED",
                    "This transfer has already been reversed, or a reversal is waiting.", List.of(),
                    maker.username(), Actions.REVERSAL_DUPLICATE_DENIED, "transfer:" + transferId, Map.of());
        }
        if (t.status() != TransferStatus.COMPLETED) {
            throw BusinessException.conflict("NOT_REVERSIBLE", "Only completed transfers can be reversed.");
        }

        String clean = reason.trim();
        if (t.amountMinor() >= thresholdMinor) {
            UUID id = reversals.insertPending(transferId, clean, maker.id());
            audit.record(maker.username(), Actions.REVERSAL_CREATED, "transfer:" + transferId,
                    Map.of("reversal_id", id, "status", "PENDING_APPROVAL", "reason", clean));
            events.info(Actions.REVERSAL_CREATED, "username", maker.username(), "transfer_id", transferId,
                    "reversal_id", id, "status", "PENDING_APPROVAL");
            return idempotency.complete(idem, HttpStatus.ACCEPTED, TransferDto.of(t));
        }

        UUID journalId = post(t, clean, maker);
        UUID id = reversals.insertPosted(transferId, clean, journalId, maker.id());
        audit.record(maker.username(), Actions.REVERSAL_CREATED, "transfer:" + transferId,
                Map.of("reversal_id", id, "status", "POSTED", "journal_id", journalId, "reason", clean));
        events.info(Actions.REVERSAL_CREATED, "username", maker.username(), "transfer_id", transferId,
                "reversal_id", id, "status", "POSTED");
        return idempotency.complete(idem, HttpStatus.CREATED, TransferDto.of(transfers.find(transferId).orElseThrow()));
    }

    public Page<ReversalDto> pending(String cursor, Integer limit) {
        int lim = Cursors.limit(limit);
        OffsetDateTime afterTs = null;
        UUID afterId = null;
        String raw = Cursors.decode(cursor);
        if (raw != null) {
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw Cursors.invalid();
            }
            try {
                afterTs = OffsetDateTime.parse(parts[0]);
                afterId = UUID.fromString(parts[1]);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw Cursors.invalid();
            }
        }
        return Page.of(reversals.pending(afterTs, afterId, lim + 1), lim, r -> r.requestedAt() + "|" + r.id());
    }

    @Transactional
    public ResponseEntity<Object> approve(AuthenticatedUser checker, UUID reversalId, String idempotencyKey) {
        String path = "/api/v1/approvals/reversals/" + reversalId + "/approve";
        IdempotencyService.Start idem = idempotency.begin(checker, idempotencyKey, "POST", path, Map.of());
        if (idem.isReplay()) {
            return idem.replay();
        }
        ReversalRepository.Row r = requirePending(checker, reversalId);
        Transfer t = transfers.findForUpdate(r.transferId()).orElseThrow();
        if (t.status() != TransferStatus.COMPLETED) {
            throw BusinessException.conflict("NOT_REVERSIBLE", "The transfer is no longer in a state that can be reversed.");
        }

        UUID journalId = post(t, r.reason(), checker);
        reversals.markPosted(reversalId, journalId, checker.id());
        audit.record(checker.username(), Actions.APPROVAL_APPROVED, "reversal:" + reversalId,
                Map.of("transfer_id", t.id(), "maker", r.requestedByUsername(), "journal_id", journalId));
        events.info(Actions.APPROVAL_APPROVED, "username", checker.username(), "reversal_id", reversalId,
                "transfer_id", t.id(), "maker", r.requestedByUsername());
        return idempotency.complete(idem, HttpStatus.OK, TransferDto.of(transfers.find(t.id()).orElseThrow()));
    }

    @Transactional
    public ResponseEntity<Object> reject(AuthenticatedUser checker, UUID reversalId, String reason, String idempotencyKey) {
        String path = "/api/v1/approvals/reversals/" + reversalId + "/reject";
        IdempotencyService.Start idem = idempotency.begin(checker, idempotencyKey, "POST", path, Map.of("reason", reason));
        if (idem.isReplay()) {
            return idem.replay();
        }
        ReversalRepository.Row r = requirePending(checker, reversalId);
        reversals.markRejected(reversalId, checker.id());
        audit.record(checker.username(), Actions.APPROVAL_REJECTED, "reversal:" + reversalId,
                Map.of("transfer_id", r.transferId(), "maker", r.requestedByUsername(), "reason", reason.trim()));
        events.info(Actions.APPROVAL_REJECTED, "username", checker.username(), "reversal_id", reversalId,
                "maker", r.requestedByUsername());
        return idempotency.complete(idem, HttpStatus.OK, reversals.dto(reversalId));
    }

    /** Moves the money back: the original recipient must still have it. */
    private UUID post(Transfer t, String reason, AuthenticatedUser by) {
        accounts.lock(t.fromAccountId(), t.toAccountId());
        if (accounts.balance(t.toAccountId()) < t.amountMinor()) {
            throw BusinessException.rule("INSUFFICIENT_FUNDS",
                    "The recipient no longer holds enough money to reverse this transfer.");
        }
        UUID journalId = ledger.postReversal(t.journalId(), t.fromAccountId(), t.toAccountId(), t.amountMinor(),
                "Reversal: " + reason, by.id());
        transfers.markReversed(t.id(), journalId);
        return journalId;
    }

    private ReversalRepository.Row requirePending(AuthenticatedUser checker, UUID reversalId) {
        ReversalRepository.Row r = reversals.findForUpdate(reversalId).orElseThrow(BusinessException::notFound);
        if (!"PENDING_APPROVAL".equals(r.status())) {
            throw BusinessException.conflict("NOT_PENDING", "This reversal has already been decided.");
        }
        if (checker.id().equals(r.requestedBy())) {
            events.warn(Actions.APPROVAL_SELF_APPROVAL_DENIED, "username", checker.username(), "reversal_id", reversalId);
            throw new RefusalException(HttpStatus.FORBIDDEN, "SELF_APPROVAL",
                    "You can't approve or reject a reversal you requested.", List.of(),
                    checker.username(), Actions.APPROVAL_SELF_APPROVAL_DENIED, "reversal:" + reversalId, Map.of());
        }
        return r;
    }
}
