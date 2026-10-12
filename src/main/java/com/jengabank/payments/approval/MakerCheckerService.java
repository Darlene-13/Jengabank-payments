package com.jengabank.payments.approval;

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
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.ledger.LedgerRepository;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.transfer.Transfer;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;
import com.jengabank.payments.transfer.TransferRepository;
import com.jengabank.payments.transfer.TransferStatus;

/**
 * Checker decisions on transfers waiting for approval. The person who created a
 * transfer can never decide it: checked here, and again by a CHECK constraint in V1.
 */
@Service
public class MakerCheckerService {

    private final TransferRepository transfers;
    private final LedgerRepository accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final SecurityEventLogger events;

    public MakerCheckerService(TransferRepository transfers, LedgerRepository accounts, LedgerService ledger,
                               IdempotencyService idempotency, AuditService audit, SecurityEventLogger events) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
    }

    public Page<TransferDto> pending(String cursor, Integer limit) {
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
        Page<Transfer> page = Page.of(transfers.page(null, TransferStatus.PENDING_APPROVAL, beforeTs, beforeId, lim + 1),
                lim, t -> t.createdAt() + "|" + t.id());
        return new Page<>(page.items().stream().map(TransferDto::of).toList(), page.nextCursor());
    }

    @Transactional
    public ResponseEntity<Object> approve(AuthenticatedUser checker, UUID transferId, String idempotencyKey) {
        String path = "/api/v1/approvals/" + transferId + "/approve";
        IdempotencyService.Start idem = idempotency.begin(checker, idempotencyKey, "POST", path, Map.of());
        if (idem.isReplay()) {
            return idem.replay();
        }
        Transfer t = requirePending(checker, transferId);

        if (accounts.lock(t.fromAccountId(), t.toAccountId()).stream().anyMatch(a -> !a.isActive())) {
            throw BusinessException.rule("ACCOUNT_NOT_ACTIVE", "One of these accounts can't send or receive money right now.");
        }
        long balance = accounts.balance(t.fromAccountId());
        if (balance < t.amountMinor()) {
            events.warn(Actions.TRANSFER_INSUFFICIENT_FUNDS, "username", checker.username(), "transfer_id", transferId,
                    "stage", "approval");
            throw new RefusalException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    "The sender no longer has enough money for this transfer. It stays pending.", List.of(),
                    checker.username(), Actions.TRANSFER_INSUFFICIENT_FUNDS, "transfer:" + transferId,
                    Map.of("stage", "approval", "amount_minor", t.amountMinor()));
        }

        UUID journalId = ledger.postTransfer(t.fromAccountId(), t.toAccountId(), t.amountMinor(),
                t.reference().isEmpty() ? "Transfer to " + t.toAccountNumber() : t.reference(), checker.id());
        transfers.markCompleted(transferId, journalId, checker.id());

        audit.record(checker.username(), Actions.APPROVAL_APPROVED, "transfer:" + transferId,
                Map.of("maker", t.createdByUsername(), "amount_minor", t.amountMinor(), "journal_id", journalId));
        events.info(Actions.APPROVAL_APPROVED, "username", checker.username(), "transfer_id", transferId,
                "maker", t.createdByUsername(), "amount_minor", t.amountMinor());
        events.info(Actions.TRANSFER_COMPLETED, "username", checker.username(), "transfer_id", transferId);
        return idempotency.complete(idem, HttpStatus.OK, TransferDto.of(transfers.find(transferId).orElseThrow()));
    }

    @Transactional
    public ResponseEntity<Object> reject(AuthenticatedUser checker, UUID transferId, String reason, String idempotencyKey) {
        String path = "/api/v1/approvals/" + transferId + "/reject";
        IdempotencyService.Start idem = idempotency.begin(checker, idempotencyKey, "POST", path, Map.of("reason", reason));
        if (idem.isReplay()) {
            return idem.replay();
        }
        Transfer t = requirePending(checker, transferId);
        transfers.markRejected(transferId, checker.id(), reason.trim());

        audit.record(checker.username(), Actions.APPROVAL_REJECTED, "transfer:" + transferId,
                Map.of("maker", t.createdByUsername(), "amount_minor", t.amountMinor(), "reason", reason.trim()));
        events.info(Actions.APPROVAL_REJECTED, "username", checker.username(), "transfer_id", transferId,
                "maker", t.createdByUsername());
        return idempotency.complete(idem, HttpStatus.OK, TransferDto.of(transfers.find(transferId).orElseThrow()));
    }

    private Transfer requirePending(AuthenticatedUser checker, UUID transferId) {
        Transfer t = transfers.findForUpdate(transferId).orElseThrow(BusinessException::notFound);
        if (t.status() != TransferStatus.PENDING_APPROVAL) {
            throw BusinessException.conflict("NOT_PENDING", "This transfer has already been decided.");
        }
        if (checker.id().equals(t.createdBy())) {
            events.warn(Actions.APPROVAL_SELF_APPROVAL_DENIED, "username", checker.username(), "transfer_id", transferId);
            throw new RefusalException(HttpStatus.FORBIDDEN, "SELF_APPROVAL",
                    "You can't approve or reject a transfer you created.", List.of(),
                    checker.username(), Actions.APPROVAL_SELF_APPROVAL_DENIED, "transfer:" + transferId,
                    Map.of("maker", t.createdByUsername()));
        }
        return t;
    }
}
