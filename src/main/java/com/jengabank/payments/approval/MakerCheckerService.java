package com.jengabank.payments.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.account.Account;
import com.jengabank.payments.account.AccountRepository;
import com.jengabank.payments.audit.AuditService;
import com.jengabank.payments.auth.Actor;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.idempotency.IdempotencyRecord;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.transfer.Transfer;
import com.jengabank.payments.transfer.TransferRepository;
import com.jengabank.payments.transfer.TransferStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MakerCheckerService {

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final SecurityEventLogger events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MakerCheckerService(TransferRepository transfers,
                               AccountRepository accounts,
                               LedgerService ledger,
                               IdempotencyService idempotency,
                               AuditService audit,
                               SecurityEventLogger events,
                               ObjectMapper mapper,
                               Clock clock) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ApprovalResponse approve(Long transferId, Actor checker, String idempotencyKey) {
        requireChecker(checker, "/approvals/" + transferId + "/approve");
        ApprovalResponse replay = replayIfCached(idempotencyKey, approvalHash("approve", transferId, checker), checker);
        if (replay != null) {
            return replay;
        }

        Transfer transfer = requireForUpdate(transferId);

        if (transfer.status() != TransferStatus.PENDING_APPROVAL) {
            return replayOrConflict(transfer, checker, idempotencyKey, "approve");
        }
        if (checker.is(transfer.createdBy())) {
            rejectSelfApproval(transfer, checker);
        }

        lockAndVerifyFunds(transfer, checker, "/approvals/" + transferId + "/approve");

        transfers.markApproved(transferId, checker.id());
        Transfer decided = transfers.findById(transferId).orElseThrow();
        ledger.post(decided);

        ApprovalResponse response = toResponse(decided, "transfer approved and journaled");
        audit.record(checker.id(), Actions.APPROVAL_APPROVE, decided.reference(), Map.of(
                "transfer_id", transferId,
                "maker", decided.createdBy(),
                "checker", checker.id(),
                "amount_minor", decided.amountMinor()));
        events.emit(Actions.APPROVAL_APPROVE, 200, checker.id(), Map.of(
                "path", "/approvals/" + transferId + "/approve",
                "transfer_id", transferId,
                "reference", decided.reference(),
                "maker", decided.createdBy(),
                "amount_minor", decided.amountMinor()));
        remember(idempotencyKey, approvalHash("approve", transferId, checker), 200, response);
        return response;
    }

    @Transactional
    public ApprovalResponse reject(Long transferId, Actor checker, String idempotencyKey) {
        requireChecker(checker, "/approvals/" + transferId + "/reject");
        ApprovalResponse replay = replayIfCached(idempotencyKey, approvalHash("reject", transferId, checker), checker);
        if (replay != null) {
            return replay;
        }

        Transfer transfer = requireForUpdate(transferId);

        if (transfer.status() != TransferStatus.PENDING_APPROVAL) {
            return replayOrConflict(transfer, checker, idempotencyKey, "reject");
        }
        if (checker.is(transfer.createdBy())) {
            rejectSelfApproval(transfer, checker);
        }

        transfers.markRejected(transferId, checker.id());
        Transfer decided = transfers.findById(transferId).orElseThrow();

        ApprovalResponse response = toResponse(decided, "transfer rejected; no journal posted");
        audit.record(checker.id(), Actions.APPROVAL_REJECT, decided.reference(), Map.of(
                "transfer_id", transferId,
                "maker", decided.createdBy(),
                "checker", checker.id(),
                "amount_minor", decided.amountMinor()));
        events.emit(Actions.APPROVAL_REJECT, 200, checker.id(), Map.of(
                "path", "/approvals/" + transferId + "/reject",
                "transfer_id", transferId,
                "reference", decided.reference(),
                "maker", decided.createdBy(),
                "amount_minor", decided.amountMinor()));
        remember(idempotencyKey, approvalHash("reject", transferId, checker), 200, response);
        return response;
    }

    public List<Transfer> pending() {
        return transfers.findByStatus(TransferStatus.PENDING_APPROVAL.name());
    }

    private void requireChecker(Actor checker, String path) {
        if (!checker.role().canApproveTransfer()) {
            events.emit(Actions.AUTH_FORBIDDEN, 403, checker.id(),
                    Map.of("path", path, "reason", "requires_role_CHECKER"));
            throw new BusinessException(HttpStatus.FORBIDDEN, Actions.AUTH_FORBIDDEN,
                    "Only CHECKER or ADMIN may decide on transfers");
        }
    }

    private Transfer requireForUpdate(Long transferId) {
        return transfers.findByIdForUpdate(transferId).orElseThrow(() -> {
            events.emit(Actions.TRANSFER_NOT_FOUND, 404, "system",
                    Map.of("transfer_id", transferId));
            return new BusinessException(HttpStatus.NOT_FOUND, Actions.TRANSFER_NOT_FOUND,
                    "No such transfer: " + transferId);
        });
    }

    private void rejectSelfApproval(Transfer transfer, Actor checker) {
        events.emit(Actions.APPROVAL_SELF_APPROVAL, 403, checker.id(), Map.of(
                "path", "/approvals/" + transfer.id() + "/approve",
                "transfer_id", transfer.id(),
                "reference", transfer.reference(),
                "maker", transfer.createdBy(),
                "checker", checker.id()));
        audit.record(checker.id(), Actions.APPROVAL_SELF_APPROVAL, transfer.reference(), Map.of(
                "transfer_id", transfer.id(),
                "maker", transfer.createdBy(),
                "checker", checker.id()));
        throw new BusinessException(HttpStatus.FORBIDDEN, Actions.APPROVAL_SELF_APPROVAL,
                "Maker " + transfer.createdBy() + " cannot approve their own transfer");
    }

    private void lockAndVerifyFunds(Transfer transfer, Actor checker, String path) {
        List<Account> locked = accounts.lockPair(transfer.sourceAccountNo(), transfer.targetAccountNo());
        if (locked.size() < 2) {
            throw new BusinessException(HttpStatus.NOT_FOUND, Actions.TRANSFER_NOT_FOUND,
                    "Accounts of transfer " + transfer.id() + " are missing");
        }
        Account source = locked.stream()
                .filter(a -> a.accountNo().equals(transfer.sourceAccountNo()))
                .findFirst()
                .orElseThrow();
        long balance = accounts.balanceOf(source.accountNo());
        if (balance < transfer.amountMinor()) {
            events.emit(Actions.APPROVAL_INSUFFICIENT_FUNDS, 422, checker.id(), Map.of(
                    "path", path,
                    "transfer_id", transfer.id(),
                    "balance_minor", balance,
                    "amount_minor", transfer.amountMinor()));
            audit.record(checker.id(), Actions.APPROVAL_INSUFFICIENT_FUNDS, transfer.reference(),
                    Map.of("balance_minor", balance, "amount_minor", transfer.amountMinor()));
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, Actions.APPROVAL_INSUFFICIENT_FUNDS,
                    "Source balance " + balance + " can no longer cover " + transfer.amountMinor());
        }
    }

    private ApprovalResponse replayOrConflict(Transfer transfer, Actor checker, String key, String operation) {
        if (checker.is(transfer.decidedBy())) {
            ApprovalResponse response = toResponse(transfer, "decision already recorded");
            events.emit(Actions.IDEMPOTENCY_REPLAY, 200, checker.id(),
                    Map.of("path", "/approvals/" + transfer.id() + "/" + operation,
                            "transfer_id", transfer.id()));
            return response;
        }
        events.emit(Actions.APPROVAL_INVALID_STATE, 409, checker.id(), Map.of(
                "path", "/approvals/" + transfer.id() + "/" + operation,
                "transfer_id", transfer.id(),
                "status", transfer.status().name()));
        throw new BusinessException(HttpStatus.CONFLICT, Actions.APPROVAL_INVALID_STATE,
                "Transfer is " + transfer.status().name() + ", expected PENDING_APPROVAL");
    }

    private ApprovalResponse replayIfCached(String key, String hash, Actor checker) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Optional<IdempotencyRecord> cached = idempotency.find(key);
        if (cached.isEmpty()) {
            return null;
        }
        IdempotencyRecord record = cached.get();
        if (!record.requestHash().equals(hash)) {
            events.emit(Actions.IDEMPOTENCY_MISMATCH, 409, checker.id(),
                    Map.of("path", "/approvals", "key", key.trim()));
            audit.record(checker.id(), Actions.IDEMPOTENCY_MISMATCH, key.trim(),
                    Map.of("payload_hash", hash));
            throw new BusinessException(HttpStatus.CONFLICT, Actions.IDEMPOTENCY_MISMATCH,
                    "Idempotency-Key was already used with a different approval request");
        }
        events.emit(Actions.IDEMPOTENCY_REPLAY, record.responseStatus(), checker.id(),
                Map.of("path", "/approvals", "key", key.trim(), "operation", "decision"));
        try {
            return mapper.readValue(record.responseBody(), ApprovalResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cached approval response is not readable", e);
        }
    }

    private void remember(String key, String hash, int status, ApprovalResponse response) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            idempotency.remember(key, hash, status, mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("approval response is not serialisable", e);
        }
    }

    private String approvalHash(String operation, Long transferId, Actor checker) {
        return idempotency.hash(operation + "|" + transferId + "|" + checker.id());
    }

    private ApprovalResponse toResponse(Transfer transfer, String message) {
        return new ApprovalResponse(
                transfer.id(),
                transfer.reference(),
                transfer.status().name(),
                transfer.fraudDecision().name(),
                transfer.decidedBy(),
                transfer.amountMinor(),
                transfer.currency(),
                transfer.decidedAt(),
                message);
    }
}