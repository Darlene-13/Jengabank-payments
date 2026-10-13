package com.jengabank.payments.approval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jengabank.payments.audit.AuditWriter;
import com.jengabank.payments.audit.RefusalException;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.ledger.LedgerService.Direction;
import com.jengabank.payments.ledger.LedgerService.Line;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;
import com.jengabank.payments.transfer.TransferRepository;
import com.jengabank.payments.transfer.TransferService;

/**
 * Maker-checker for transfers held as PENDING_APPROVAL (amount at or above the threshold, or fraud REVIEW).
 * Approve: re-check accounts and balance under lock, post the journal, mark COMPLETED.
 * Reject: mark REJECTED with a reason; no money moves.
 * The creator can never decide: checked here, and again by a CHECK constraint in V1.
 */
@Service
public class MakerCheckerService {

    private record PendingTransfer(UUID id, UUID fromAccountId, UUID toAccountId, long amountMinor,
                                   String reference, String status, UUID createdBy) {}

    private final JdbcTemplate jdbc;
    private final TransferService transfers;
    private final TransferRepository transferRepo;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final SecurityEventLogger events;

    public MakerCheckerService(JdbcTemplate jdbc, TransferService transfers, TransferRepository transferRepo,
                               LedgerService ledger, IdempotencyService idempotency, AuditWriter audit,
                               SecurityEventLogger events) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.transferRepo = transferRepo;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
    }

    /** The checker queue: every PENDING_APPROVAL transfer, newest first. */
    public Page<TransferDto> pending(AuthenticatedUser checker, String cursor, Integer limit) {
        return transfers.list(checker, "PENDING_APPROVAL", cursor, limit);
    }

    @Transactional
    public ResponseEntity<?> approve(AuthenticatedUser checker, UUID transferId, String key) {
        String endpoint = "POST /api/v1/approvals/" + transferId + "/approve";
        return idempotency.execute(checker, key, endpoint, Map.of(), () -> {
            PendingTransfer t = lockPending(checker, transferId);

            ledger.lockAccounts(t.fromAccountId(), t.toAccountId());
            Integer inactive = jdbc.queryForObject(
                    "SELECT count(*) FROM accounts WHERE id IN (?, ?) AND status <> 'ACTIVE'",
                    Integer.class, t.fromAccountId(), t.toAccountId());
            if (inactive != null && inactive > 0) {
                throw BusinessException.rule("ACCOUNT_NOT_ACTIVE",
                        "One of these accounts can't send or receive money right now.");
            }

            // Funds are not reserved while pending, so re-check under lock before money moves.
            if (ledger.balance(t.fromAccountId()) < t.amountMinor()) {
                events.warn(Actions.TRANSFER_INSUFFICIENT_FUNDS, "username", checker.username(),
                        "transfer_id", transferId, "stage", "approval");
                throw new RefusalException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                        "The sending account no longer has enough money. Reject this transfer.", List.of(),
                        checker.username(), Actions.TRANSFER_INSUFFICIENT_FUNDS, "transfer/" + transferId,
                        Map.of("stage", "approval", "amount_minor", t.amountMinor()));
            }

            String description = t.reference() == null || t.reference().isBlank() ? "Transfer" : t.reference();
            UUID journalId = ledger.post("TRANSFER", description, null, checker.id(), List.of(
                    new Line(t.fromAccountId(), Direction.DEBIT, t.amountMinor()),
                    new Line(t.toAccountId(), Direction.CREDIT, t.amountMinor())));

            int updated = jdbc.update("""
                    UPDATE transfers SET status = 'COMPLETED', journal_id = ?, decided_by = ?, decided_at = now()
                     WHERE id = ? AND status = 'PENDING_APPROVAL'""", journalId, checker.id(), transferId);
            if (updated != 1) {
                throw BusinessException.conflict("INVALID_STATE", "This transfer has already been decided.");
            }

            audit.write(checker.username(), Actions.APPROVAL_APPROVED, "transfer/" + transferId,
                    Map.of("type", "transfer", "journal_id", journalId.toString(), "amount_minor", t.amountMinor()));
            events.info(Actions.TRANSFER_COMPLETED, "username", checker.username(), "transfer_id", transferId,
                    "journal_id", journalId, "amount_minor", t.amountMinor());
            events.info(Actions.APPROVAL_APPROVED, "username", checker.username(), "type", "transfer",
                    "transfer_id", transferId, "journal_id", journalId);
            return ResponseEntity.ok(transferRepo.find(transferId).orElseThrow());
        });
    }

    @Transactional
    public ResponseEntity<?> reject(AuthenticatedUser checker, UUID transferId, RejectTransferRequest body, String key) {
        String endpoint = "POST /api/v1/approvals/" + transferId + "/reject";
        return idempotency.execute(checker, key, endpoint, body, () -> {
            lockPending(checker, transferId);
            String reason = body.reason().trim();
            int updated = jdbc.update("""
                    UPDATE transfers SET status = 'REJECTED', rejection_reason = ?, decided_by = ?, decided_at = now()
                     WHERE id = ? AND status = 'PENDING_APPROVAL'""", reason, checker.id(), transferId);
            if (updated != 1) {
                throw BusinessException.conflict("INVALID_STATE", "This transfer has already been decided.");
            }
            audit.write(checker.username(), Actions.APPROVAL_REJECTED, "transfer/" + transferId,
                    Map.of("type", "transfer", "reason", reason));
            events.info(Actions.APPROVAL_REJECTED, "username", checker.username(), "type", "transfer",
                    "transfer_id", transferId);
            return ResponseEntity.ok(transferRepo.find(transferId).orElseThrow());
        });
    }

    private PendingTransfer lockPending(AuthenticatedUser checker, UUID transferId) {
        PendingTransfer t = jdbc.query("""
                SELECT id, from_account_id, to_account_id, amount_minor, reference, status, created_by
                  FROM transfers WHERE id = ? FOR UPDATE""",
                (rs, n) -> new PendingTransfer(
                        rs.getObject("id", UUID.class),
                        rs.getObject("from_account_id", UUID.class),
                        rs.getObject("to_account_id", UUID.class),
                        rs.getLong("amount_minor"),
                        rs.getString("reference"),
                        rs.getString("status"),
                        rs.getObject("created_by", UUID.class)),
                transferId).stream().findFirst().orElseThrow(BusinessException::notFound);

        if (!"PENDING_APPROVAL".equals(t.status())) {
            events.warn(Actions.REQUEST_INVALID, "username", checker.username(), "transfer_id", transferId,
                    "reason", "approval_invalid_state_" + t.status());
            throw BusinessException.conflict("INVALID_STATE", "This transfer has already been decided.");
        }
        if (t.createdBy().equals(checker.id())) {
            events.warn(Actions.APPROVAL_SELF_APPROVAL_DENIED, "username", checker.username(),
                    "type", "transfer", "transfer_id", transferId);
            throw new RefusalException(HttpStatus.FORBIDDEN, "SELF_APPROVAL",
                    "You created this transfer, so another approver must decide.", List.of(),
                    checker.username(), Actions.APPROVAL_SELF_APPROVAL_DENIED, "transfer/" + transferId,
                    Map.of("type", "transfer"));
        }
        return t;
    }
}
